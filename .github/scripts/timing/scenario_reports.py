"""Publish optional packaged-harness timing records without scraping logs."""

import argparse
import csv
import json
import math
import os
from pathlib import Path
import re

import report


def collect(path, expected_backends):
    data = {"schema_version": 1, "scenarios": [], "issues": []}
    active = {}
    completed = set()
    try:
        with Path(path).open(encoding="utf-8", newline="") as stream:
            for fields in csv.reader(stream, delimiter="\t"):
                try:
                    runtime, backend, scenario, outcome, seconds = fields
                    if (runtime not in ("jvm", "native")
                            or backend not in ("postgresql", "file", "memory", "disabled")
                            or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", scenario)):
                        raise ValueError("invalid_identity")
                    key = (runtime, backend, scenario)
                    if outcome == "started" and not seconds and key not in active and key not in completed:
                        row = {"runtime": runtime, "backend": backend, "scenario": scenario,
                               "outcome": "incomplete", "seconds": None}
                        active[key] = row
                        data["scenarios"].append(row)
                    elif outcome in ("passed", "failed") and key in active:
                        duration = float(seconds)
                        if not math.isfinite(duration) or duration < 0:
                            raise ValueError("invalid_duration")
                        active.pop(key).update(outcome=outcome, seconds=duration)
                        completed.add(key)
                    else:
                        raise ValueError("invalid_sequence")
                except ValueError:
                    data["issues"].append({"source": "packaged scenarios", "reason": "invalid_record"})
    except (OSError, UnicodeError, csv.Error):
        data["issues"].append({"source": "packaged scenarios", "reason": "missing_reports"})
    for runtime, backend, scenario in active:
        data["issues"].append({"source": f"{runtime}/{backend}/{scenario}", "reason": "unfinished_scenario"})
    for backend in expected_backends:
        if not any(row["backend"] == backend and row["scenario"] == "packaged" for row in data["scenarios"]):
            data["issues"].append({"source": backend, "reason": "missing_backend"})
    return data


def summary(data):
    lines = ["## Packaged JVM scenario timings", "",
             "**Report data: " + ("incomplete" if data["issues"] else "available") + "**", "",
             "Scenario elapsed intervals **overlap** the enclosing `packaged` interval; do not sum them.",
             "`packaged` includes harness setup and cleanup, but excludes Maven startup/compilation.",
             "Unfinished scenarios have unavailable duration; unstarted modes are not passes.",
             "This is a partial job snapshot, not final job elapsed or runner usage.", ""]
    rows = data["scenarios"]
    lines += report.table(["Runtime", "Backend", "Scenario", "Outcome", "Seconds"], [
        [row["runtime"], row["backend"], row["scenario"], row["outcome"],
         f"{row['seconds']:.3f}" if row["seconds"] is not None else "unavailable"]
        for row in rows[:25]
    ])
    if len(rows) > 25:
        lines += ["", "Additional scenario rows are in the artifact."]
    if data["issues"]:
        lines += ["", "### Incomplete data", ""]
        lines += [f"- {report.markdown(issue['source'])}: {report.markdown(issue['reason'])}"
                  for issue in data["issues"][:25]]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-backend", action="append", default=[])
    args = parser.parse_args()
    try:
        data = collect(args.input, args.expected_backend)
        data["identity"] = report.identity(os.environ)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / "scenarios.json").write_text(json.dumps(data, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        with (args.output / "scenarios.csv").open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream)
            fields = ("runtime", "backend", "scenario", "outcome", "seconds")
            writer.writerow(fields)
            for row in data["scenarios"]:
                writer.writerow(report.csv_cell(row[field] if row[field] is not None else "") for field in fields)
        text = summary(data)
        (args.output / "scenarios.md").write_text(text, encoding="utf-8")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as stream:
                stream.write(text)
        if data["issues"]:
            print("::warning::Packaged scenario timing data incomplete; see timing artifact.")
    except (OSError, ValueError):
        print("::warning::Packaged scenario timing publication failed; verification result unchanged.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
