"""Small, dependency-free output helpers for CI timing metadata."""

import csv
import html
import json
from pathlib import Path


def identity(environment):
    keys = {
        "repository": "GITHUB_REPOSITORY", "run_id": "GITHUB_RUN_ID",
        "run_attempt": "GITHUB_RUN_ATTEMPT", "commit": "GITHUB_SHA",
        "ref": "GITHUB_REF", "event": "GITHUB_EVENT_NAME", "job": "GITHUB_JOB",
        "runner_os": "RUNNER_OS", "runner_arch": "RUNNER_ARCH",
        "runner_environment": "RUNNER_ENVIRONMENT", "runner_label": "TIMING_RUNNER_LABEL",
        "runner_image": "ImageOS", "runner_image_version": "ImageVersion",
    }
    result = {name: environment.get(key) or None for name, key in keys.items()}
    if result["repository"] and result["run_id"]:
        result["run_url"] = (environment.get("GITHUB_SERVER_URL", "https://github.com").rstrip("/")
                             + f"/{result['repository']}/actions/runs/{result['run_id']}")
    return result


def text(value):
    return "".join(char if char.isprintable() else " " for char in str(value))


def markdown(value):
    return html.escape(text(value), quote=True).replace("|", "&#124;")


def csv_cell(value):
    value = text(value)
    return "'" + value if value.lstrip().startswith(("=", "+", "-", "@")) else value


def table(headers, rows):
    return ["| " + " | ".join(headers) + " |",
            "|" + "|".join("---" for _ in headers) + "|",
            *["| " + " | ".join(markdown(cell) for cell in row) + " |" for row in rows]]


def summary(data):
    issues = data.get("issues", [])
    build = data.get("build", {})
    seconds = build.get("elapsed_seconds")
    counts = data.get("counts", {})
    provenance = data.get("identity", {})
    lines = ["## JVM verification timings", "",
             "**Report data: " + ("incomplete" if issues else "available") + "**", ""]
    lines += table(["Metric", "Value"], [
        ("Maven verify elapsed (build + tests)", f"{seconds:.3f}s" if seconds is not None else "unavailable"),
        ("Maven step outcome", build.get("outcome") or "unavailable"),
        ("Aggregate suite execution (valid reports only)",
         f"{data['aggregate_suite_seconds']:.3f}s" if data.get("suites") else "unavailable"),
        ("Reported test executions / failures / errors / skipped",
         " / ".join(str(counts.get(key, "unavailable")) for key in ("tests", "failures", "errors", "skipped"))),
    ])
    lines += ["", "Suite execution is an aggregate, **not wall-clock time**. Maven verify includes",
              "dependency resolution, build, tests and packaging; no compile-only duration is inferred.",
              "This is a **partial job snapshot**: final job elapsed, queue/wait and artifact-upload",
              "overhead require completed-run Actions data. Missing values are not estimated.", ""]
    if provenance:
        lines += ["### Run identity", ""]
        lines += table(["Field", "Value"], [(key, value if value is not None else "unavailable")
                                          for key, value in provenance.items()])
        lines += [""]
    for title, category, fields in (
        ("Slowest 25 suites", "suites", ("module", "phase", "suite", "tests", "seconds")),
        ("Slowest 25 test executions", "testcases", ("module", "phase", "suite", "test", "outcome", "seconds")),
    ):
        lines += [f"### {title}", ""]
        rows = sorted(data.get(category, []), key=lambda row: row["seconds"], reverse=True)[:25]
        if rows:
            lines += table(list(fields), [[f"{row[field]:.3f}" if field == "seconds" else row[field]
                                          for field in fields] for row in rows])
        else:
            lines += ["Unavailable: no valid execution rows collected."]
        lines += [""]
    if issues:
        lines += ["### Incomplete data", ""]
        lines += [f"- {markdown(issue['source'])}: {markdown(issue['reason'])}" for issue in issues]
        lines += [""]
    lines += ["Full extracted timing rows are in the JVM timing artifact; raw XML properties,",
              "failure bodies and system output are not copied. Parameterized test arguments are omitted.", ""]
    return "\n".join(lines)


def write_report(data, directory, summary_path=None):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "timing.json").write_text(json.dumps(data, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    for filename, category, fields in (
        ("suites.csv", "suites", ("module", "phase", "suite", "tests", "failures", "errors", "skipped", "seconds")),
        ("testcases.csv", "testcases", ("module", "phase", "suite", "test", "execution", "outcome", "seconds")),
    ):
        with (directory / filename).open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream)
            writer.writerow(fields)
            for row in data.get(category, []):
                writer.writerow(csv_cell(row[field]) for field in fields)
    rendered = summary(data)
    (directory / "summary.md").write_text(rendered, encoding="utf-8")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as stream:
            stream.write(rendered)
