"""Small AWS CLI transport shared by the protected controller and private nodes."""

import json
from contextlib import ExitStack
from pathlib import Path
import re
import subprocess
import tempfile


class AwsError(RuntimeError):
    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


def failure(service, operation, parameters, stderr):
    stderr = stderr.decode("utf-8", errors="replace") if isinstance(stderr, bytes) else stderr or ""
    match = re.search(r"An error occurred \(([A-Za-z0-9_.-]+)\)", stderr)
    code = match[1] if match else "AWS_REQUEST_FAILED"
    if (service == "cloudformation" and operation in ("describe-stacks", "delete-stack") and code == "ValidationError"
            and ("Stack with id " + str(parameters.get("stack_name")) + " does not exist") in stderr):
        code = "StackNotFound"
    return AwsError(service + ":" + operation + " failed", code=code)


class AwsCli:
    def __init__(self, region):
        self.region = region
        self.timeout_seconds = 120

    def call(self, service, operation, **parameters):
        with ExitStack() as resources:
            command = ["aws", service, operation, "--region", self.region, "--output", "json", "--no-cli-pager", "--no-paginate"]
            directory = None
            for key, value in parameters.items():
                command.append("--" + key.replace("_", "-"))
                structured = isinstance(value, (dict, list))
                encoded = json.dumps(value) if structured else str(value)
                if structured and len(encoded.encode("utf-8")) > 8192:
                    # S3 batches can exceed OS argument limits even within the 1,000-key API limit.
                    if directory is None:
                        directory = Path(resources.enter_context(tempfile.TemporaryDirectory()))
                    path = directory / (key + ".json")
                    path.write_text(encoded, encoding="utf-8")
                    encoded = "file://" + str(path)
                command.append(encoded)
            return self._request(command, service, operation, parameters)

    def _request(self, command, service, operation, parameters):
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=self.timeout_seconds, check=True)
            return json.loads(result.stdout) if result.stdout.strip() else {}
        except subprocess.CalledProcessError as error:
            raise failure(service, operation, parameters, error.stderr) from None
        except (OSError, ValueError, subprocess.SubprocessError):
            # Do not publish AWS stderr, command arguments, or secret values.
            raise AwsError(service + ":" + operation + " failed") from None

    def put(self, bucket, key, path, immutable=False):
        parameters = {"bucket": bucket, "key": key, "body": str(path)}
        if immutable:
            parameters["if_none_match"] = "*"
        return self.call("s3api", "put-object", **parameters)

    def get(self, bucket, key, path):
        # get-object's destination is positional, unlike the JSON API parameters.
        command = ["aws", "s3api", "get-object", "--region", self.region, "--no-cli-pager",
                   "--bucket", bucket, "--key", key, str(path)]
        try:
            subprocess.run(command, capture_output=True, timeout=self.timeout_seconds, check=True)
        except subprocess.CalledProcessError as error:
            Path(path).unlink(missing_ok=True)
            raise failure("s3api", "get-object", {"bucket": bucket, "key": key}, error.stderr) from None
        except (OSError, subprocess.SubprocessError):
            Path(path).unlink(missing_ok=True)
            raise AwsError("s3api:get-object failed") from None
