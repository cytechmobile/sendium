"""Resolve and checksum runtime bytes. Downloaded archives are never executed here."""

import hashlib
import json
from pathlib import Path
import re
import time
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


HOSTS = {"api.adoptium.net", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
         "truststore.pki.rds.amazonaws.com"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_url(url):
    parsed = urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname in HOSTS and not parsed.username and not parsed.password,
            "Runtime download is not an approved HTTPS source")
    require(parsed.port in (None, 443), "Unexpected runtime HTTPS port")


class Redirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        validate_url(url)
        return super().redirect_request(request, response, code, message, headers, url)


class Downloads:
    def __init__(self):
        self.opener = build_opener(Redirects())

    def open(self, url):
        validate_url(url)
        return self.opener.open(Request(url, headers={"User-Agent": "sendium-performance-v1"}), timeout=30)

    def metadata(self, url):
        with self.open(url) as response:
            body = response.read(4 * 1024 * 1024 + 1)
        require(len(body) <= 4 * 1024 * 1024, "Runtime metadata exceeds budget")
        return json.loads(body)

    def download(self, url, target, maximum, checksum=None, size=None):
        target = Path(target)
        digest, count = hashlib.sha256(), 0
        deadline = time.monotonic() + 300
        created = False
        try:
            with target.open("xb") as output:
                created = True
                with self.open(url) as response:
                    while True:
                        require(time.monotonic() < deadline, "Runtime download deadline exceeded")
                        block = response.read(1024 * 1024)
                        if not block:
                            break
                        count += len(block)
                        require(count <= maximum, "Runtime download exceeds size budget")
                        digest.update(block)
                        output.write(block)
            require(count > 0 and (size is None or count == size) and (checksum is None or digest.hexdigest() == checksum),
                    "Runtime download size/checksum mismatch")
        except Exception:
            if created:
                target.unlink(missing_ok=True)
            raise
        return {"path": str(target.resolve()), "sha256": digest.hexdigest(), "size": count, "url": url}


def jre_metadata(downloads, architecture, release=None):
    archive_architecture = {"arm64": "aarch64", "x86_64": "x64"}[architecture]
    query = urlencode({"architecture": archive_architecture, "image_type": "jre", "os": "linux", "vendor": "eclipse"})
    if release:
        name = release if release.startswith("jdk-") else "jdk-" + release
        require(re.fullmatch(r"jdk-25(?:\.[0-9]+){0,3}\+[0-9]+", name), "Exact Java 25 release/build required")
        url = "https://api.adoptium.net/v3/assets/release_name/eclipse/" + quote(name, safe="") + "?" + query
    else:
        name = None
        url = "https://api.adoptium.net/v3/assets/latest/25/hotspot?" + query
    response = downloads.metadata(url)
    records = response if isinstance(response, list) else [response]
    candidates = []
    for record in records:
        release_name = record["release_name"]
        if name and release_name != name:
            continue
        version = record.get("version", record.get("version_data", {}))
        require(version.get("major") == 25 and re.fullmatch(r"jdk-25(?:\.[0-9]+){0,3}\+[0-9]+", release_name), "Unexpected Java release")
        for binary in ([record["binary"]] if "binary" in record else record["binaries"]):
            if (binary["architecture"] == archive_architecture and binary["image_type"] == "jre"
                    and binary["os"] == "linux" and binary["jvm_impl"] == "hotspot"):
                package = binary["package"]
                validate_url(package["link"])
                location = urlsplit(package["link"])
                require(location.scheme == "https" and location.hostname == "github.com"
                        and location.path.startswith("/adoptium/temurin25-binaries/releases/download/")
                        and location.path.endswith(".tar.gz") and not location.query and not location.fragment,
                        "Unexpected JRE package source")
                require(re.fullmatch(r"[a-f0-9]{64}", package["checksum"])
                        and type(package["size"]) is int and 0 < package["size"] <= 1024 ** 3, "Invalid JRE checksum/size")
                candidates.append({"architecture": architecture, "release": release_name, "distribution": "Eclipse Temurin",
                                   "sha256": package["checksum"], "size": package["size"], "url": package["link"]})
    require(len(candidates) == 1, "Expected one exact compatible Java runtime archive")
    return candidates[0]


def resolve(downloads, nodes, preset, directory, target="sendium"):
    directory = Path(directory)
    directory.mkdir(exist_ok=False)
    artifacts, runtime = {}, {}
    selected_release = preset["runtime"]["java"]["release"]
    cache = {}
    require(target in ("sendium", "kannel"), "Unknown benchmark target")
    for source in (("harness",) if target == "kannel" else ("sendium", "harness")):
        architecture = nodes[source]["architecture"]
        if architecture not in cache:
            metadata = jre_metadata(downloads, architecture, selected_release)
            selected_release = metadata["release"]
            asset = downloads.download(metadata["url"], directory / ("jre-" + architecture + ".tar.gz"), 1024 ** 3,
                                       checksum=metadata["sha256"], size=metadata["size"])
            cache[architecture] = (metadata, asset)
        metadata, asset = cache[architecture]
        artifacts[source + "Jre"] = {key: asset[key] for key in ("path", "sha256")}
        runtime[source] = metadata
    ca = downloads.download("https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem", directory / "global-bundle.pem", 1024 * 1024)
    content = (directory / "global-bundle.pem").read_bytes()
    require(b"-----BEGIN CERTIFICATE-----" in content and b"-----END CERTIFICATE-----" in content, "RDS CA bundle is not PEM")
    artifacts["rdsCa"] = {key: ca[key] for key in ("path", "sha256")}
    return artifacts, {"java": runtime, "rdsCa": {key: ca[key] for key in ("url", "sha256", "size")}}
