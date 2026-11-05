"""Database section for reports whose packaged JVM renderer is unavailable."""

import html


def database_markdown(report):
    database = report["resources"].get("database")
    if database is None and not report["configuration"]["infrastructure"]["database"].get("resourceMetricsRequired", False):
        return ""
    lines = ["", "## Database resources (RDS)", ""]
    if database is None:
        return "\n".join(lines + ["RDS resource evidence is unavailable. Utilization is **n/a**, not zero.", ""])
    def text(value):
        return html.escape(str(value)).replace("|", "&#124;").replace("`", "'").replace("\n", " ").replace("\r", " ")
    def unit(metric):
        return {"Bytes": "MiB", "Bytes/Second": "MiB/s", "Seconds": "ms", "Percent": "%"}.get(
            metric["unit"], "credits" if "Credit" in metric["name"] else metric["unit"])
    def values(metric, data):
        scale = {"Bytes": 1 / 1048576, "Bytes/Second": 1 / 1048576, "Seconds": 1000}.get(metric["unit"], 1)
        return ["n/a" if data.get(field) is None else format(data[field] * scale, ".3f") for field in ("average", "minimum", "maximum", "sum")]
    lines += ["Database: `" + text(database["database"]["identifier"]) + "` · Ownership verified: " + str(database["database"]["verified"]).lower() + ".", "",
              "Measurement statistics use only complete CloudWatch buckets fully contained in measurement. Averages are weighted by CloudWatch SampleCount. Boundary buckets are excluded from statistics and retained in the timeline; a short phase can have coverage but no phase-only statistics.", "",
              "Most metrics use 60-second buckets; CPU credits use 300-second buckets. Storage I/O latency is not SQL-query latency. Freeable memory is not heap usage. One CPU credit represents one vCPU-minute. Missing evidence is never zero-filled.", "",
              "| Metric | Unit | Period s | Avg | Min | Max | Sum | Measurement buckets complete/expected | Contained complete buckets | Boundary buckets |",
              "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for metric in database["metrics"]:
        summary = metric["summary"]["measurement"]
        coverage = "unknown" if metric["applicable"] is None else "not applicable" if not metric["applicable"] \
            else str(summary["completeBuckets"]) + "/" + str(summary["expectedBuckets"])
        row = [text(metric["name"]), text(unit(metric)), str(metric["periodSeconds"]), *values(metric, summary), coverage,
               str(summary["containedBuckets"]), str(summary["boundaryBuckets"])]
        lines.append("| " + " | ".join(row) + " |")
    lines += ["", "<details><summary>Database timeline — whole CloudWatch buckets</summary>", "",
              "Buckets can overlap multiple phases or extend outside observation. Their values describe the entire bucket, not a prorated phase value. Full phase summaries, coverage and collection issues are in results.json.", "",
              "| Metric | Bucket start UTC | Period s | Overlapping phases | Crosses phase boundary | Avg | Min | Max | Sum |",
              "|---|---|---:|---|---|---:|---:|---:|---:|"]
    shown, total = 0, sum(len(metric["datapoints"]) for metric in database["metrics"])
    for metric in database["metrics"]:
        for point in metric["datapoints"]:
            if shown >= 240:
                break
            shown += 1
            row = [text(metric["name"]) + " (" + text(unit(metric)) + ")", text(point["timestamp"]), str(metric["periodSeconds"]),
                   text(", ".join(point["phases"])), str(point["crossesPhaseBoundary"]).lower(), *values(metric, point)]
            lines.append("| " + " | ".join(row) + " |")
    if shown < total:
        lines += ["", "Showing " + str(shown) + " of " + str(total) + " buckets; the complete timeline is in results.json."]
    lines += ["", "</details>"]
    lines += ["- Database collection: " + text(issue) for issue in database["collectionIssues"]]
    lines += ["- " + text(metric["name"]) + ": " + text(issue) for metric in database["metrics"] for issue in metric["collectionIssues"]]
    return "\n".join(lines) + "\n"
