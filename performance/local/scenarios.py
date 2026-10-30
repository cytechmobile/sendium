"""DLR-enabled normal runs are the default; other scenarios require explicit selection."""

DEFAULT = ("http-dlr", "smpp-dlr")
LOAD = ("http-dlr", "http-no-dlr", "smpp-dlr", "smpp-no-dlr")
ALL = (*LOAD, "database-unavailable", "bad-tls", "app-crash", "interrupted", "partial-stage", "partial-create", "abandoned", "concurrent")
KANNEL = ("http-dlr", "http-no-dlr", "app-crash", "interrupted", "database-unavailable", "bad-tls")
WORKLOAD_ARGUMENTS = {
    "warmup_seconds": ("warmupSeconds", 0, "maximumWarmupSeconds"),
    "measurement_seconds": ("measurementSeconds", 1, "maximumMeasurementSeconds"),
    "drain_seconds": ("drainTimeoutSeconds", 0, "maximumDrainSeconds"),
    "worker_tps": ("workerTps", 0, None),
    "provider_response_delay_ms": ("providerResponseDelayMillis", 0, "maximumProviderResponseDelayMillis"),
    "provider_dlr_delay_ms": ("providerDlrDelayMillis", 0, "maximumProviderDlrDelayMillis"),
}


def select(value, target="sendium"):
    if target == "kannel":
        result = ("http-dlr",) if value == "load" else tuple(value.split(","))
        if not result or len(set(result)) != len(result) or any(name not in KANNEL for name in result):
            raise ValueError("Kannel local scenarios support HTTP ingress only: " + ", ".join(KANNEL))
        return result
    if target != "sendium":
        raise ValueError("Unknown benchmark target")
    result = DEFAULT if value == "load" else ALL if value == "all" else tuple(value.split(","))
    if not result or any(name not in ALL for name in result):
        raise ValueError("Unknown local scenario")
    return result


def add_workload_arguments(parser):
    for name in WORKLOAD_ARGUMENTS:
        parser.add_argument("--" + name.replace("_", "-"), type=int, help="Override the shared preset for this local run")


def workload_overrides(arguments, preset):
    result = {}
    for name, (_, minimum, bound) in WORKLOAD_ARGUMENTS.items():
        value = getattr(arguments, name, None)
        maximum = preset["bounds"][bound] if bound else 2147483647
        if value is not None:
            if type(value) is not int or not minimum <= value <= maximum:
                raise ValueError("Local workload override is outside preset bounds: " + name)
            result[name] = value
    return result
