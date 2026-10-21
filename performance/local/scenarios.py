"""DLR-enabled normal runs are the default; other scenarios require explicit selection."""

DEFAULT = ("http-dlr", "smpp-dlr")
LOAD = ("http-dlr", "http-no-dlr", "smpp-dlr", "smpp-no-dlr")
ALL = (*LOAD, "database-unavailable", "bad-tls", "app-crash", "interrupted", "partial-stage", "partial-create", "abandoned", "concurrent")


def select(value):
    result = DEFAULT if value == "load" else ALL if value == "all" else tuple(value.split(","))
    if not result or any(name not in ALL for name in result):
        raise ValueError("Unknown local scenario")
    return result
