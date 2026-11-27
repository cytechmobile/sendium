"""Extract allowlisted timing fields from existing Surefire/Failsafe XML."""

import argparse
from datetime import datetime, timezone
import math
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

import report


def duration(value):
    if "," in value and not re.fullmatch(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?", value):
        raise ValueError("invalid_duration")
    result = float(value.replace(",", ""))
    if not math.isfinite(result) or result < 0:
        raise ValueError("invalid_duration")
    return result


def counts(element):
    result = {key: int(element.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    if any(value < 0 for value in result.values()):
        raise ValueError("invalid_count")
    return result


def parse_file(path, module, phase):
    content = path.read_bytes()
    declarations = content.replace(b"\x00", b"").upper()
    if b"<!DOCTYPE" in declarations or b"<!ENTITY" in declarations:
        raise ValueError("unsupported_dtd")
    root = ET.fromstring(content)
    leaves = [suite for suite in root.iter("testsuite") if not suite.findall(".//testsuite")]
    if not leaves:
        raise ValueError("missing_suite")
    suites, testcases = [], []
    for suite in leaves:
        base = {"module": module, "phase": phase, "suite": report.text(suite.attrib["name"])}
        suites.append({**base, **counts(suite), "seconds": duration(suite.attrib["time"])})
        for execution, case in enumerate(suite.findall("testcase"), start=1):
            # Method identifiers are useful; parameter values and display text may contain private data.
            identifier = re.match(r"([A-Za-z_$][A-Za-z0-9_$]*)(?=$|[\[(])", case.get("name", ""))
            outcome = ("failed" if case.find("failure") is not None else
                       "error" if case.find("error") is not None else
                       "skipped" if case.find("skipped") is not None else "passed")
            testcases.append({**base, "test": identifier[1] if identifier else "parameterized-test",
                              "execution": execution, "outcome": outcome,
                              "seconds": duration(case.attrib["time"])})
    return suites, testcases


def collect(root, directories):
    result = {"schema_version": 1, "suites": [], "testcases": [], "issues": [],
              "counts": dict.fromkeys(("tests", "failures", "errors", "skipped"), 0),
              "aggregate_suite_seconds": 0}
    root = Path(root)
    for directory in directories:
        directory = Path(directory)
        paths = sorted((root / directory).glob("TEST-*.xml"))
        if not paths:
            result["issues"].append({"source": directory.as_posix(), "reason": "missing_reports"})
        for path in paths:
            source = (directory / path.name).as_posix()
            try:
                suites, testcases = parse_file(path, directory.parent.parent.as_posix(),
                                              directory.name.removesuffix("-reports"))
            except (OSError, ET.ParseError, KeyError, ValueError):
                result["issues"].append({"source": source, "reason": "invalid_report"})
                continue
            result["suites"].extend(suites)
            result["testcases"].extend(testcases)
            for suite in suites:
                result["aggregate_suite_seconds"] += suite["seconds"]
                for key in result["counts"]:
                    result["counts"][key] += suite[key]
    if not result["suites"]:
        result["counts"] = {}
        result["aggregate_suite_seconds"] = None
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--reports", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-seconds", default="")
    parser.add_argument("--build-outcome", default="")
    args = parser.parse_args()
    try:
        data = collect(args.root, args.reports)
        data["identity"] = report.identity(os.environ)
        data["captured_at"] = datetime.now(timezone.utc).isoformat()
        data["build"] = {"elapsed_seconds": None, "outcome": args.build_outcome or None}
        try:
            data["build"]["elapsed_seconds"] = duration(args.build_seconds)
        except ValueError:
            data["issues"].append({"source": "Maven verify", "reason": "build_timing_unavailable"})
        report.write_report(data, args.output, os.environ.get("GITHUB_STEP_SUMMARY"))
        if data["issues"]:
            print(f"::warning::JVM timing data incomplete ({len(data['issues'])} issue(s)); see timing summary.")
    except (OSError, ValueError):
        # Never echo third-party exception text; it can include report data or host paths.
        print("::warning::JVM timing publication failed; original verification result is unchanged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
