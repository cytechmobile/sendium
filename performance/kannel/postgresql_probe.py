"""Verify libpq connectivity, server identity, TLS and DLR schema inside the gateway."""

import ctypes
import ctypes.util


def check(settings, schema=None):
    library = ctypes.CDLL(ctypes.util.find_library("pq") or "libpq.so.5")
    pointer = ctypes.c_void_p
    library.PQconnectdbParams.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p), ctypes.c_int]
    library.PQconnectdbParams.restype = pointer
    library.PQstatus.argtypes = [pointer]
    library.PQfinish.argtypes = [pointer]
    library.PQexec.argtypes = [pointer, ctypes.c_char_p]
    library.PQexec.restype = pointer
    library.PQresultStatus.argtypes = [pointer]
    library.PQntuples.argtypes = [pointer]
    library.PQgetvalue.argtypes = [pointer, ctypes.c_int, ctypes.c_int]
    library.PQgetvalue.restype = ctypes.c_char_p
    library.PQclear.argtypes = [pointer]
    values = {"host": settings["host"], "port": str(settings["port"]), "user": settings["username"],
              "password": settings["password"], "dbname": settings["database"], "connect_timeout": "5",
              "sslmode": "verify-full", "sslrootcert": settings["caFile"], "application_name": "kannel-readiness"}
    keys = (ctypes.c_char_p * (len(values) + 1))(*[key.encode() for key in values], None)
    data = (ctypes.c_char_p * (len(values) + 1))(*[value.encode() for value in values.values()], None)
    connection = library.PQconnectdbParams(keys, data, 0)
    if not connection:
        raise RuntimeError("POSTGRESQL_READINESS_FAILED")
    try:
        if library.PQstatus(connection) != 0:
            raise RuntimeError("POSTGRESQL_READINESS_FAILED")
        if schema is not None:
            result = library.PQexec(connection, schema.encode())
            if not result:
                raise RuntimeError("POSTGRESQL_SCHEMA_INSTALL_FAILED")
            try:
                if library.PQresultStatus(result) != 1:
                    raise RuntimeError("POSTGRESQL_SCHEMA_INSTALL_FAILED")
            finally:
                library.PQclear(result)
        result = library.PQexec(connection, b"SELECT current_setting('server_version_num'), current_setting('server_version'), "
                                b"(SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()), "
                                b"to_regclass('public.dlr') IS NOT NULL AND EXISTS "
                                b"(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='dlr' "
                                b"AND column_name='oid' AND is_identity='YES')")
        if not result:
            raise RuntimeError("POSTGRESQL_SCHEMA_READINESS_FAILED")
        try:
            if library.PQresultStatus(result) != 2 or library.PQntuples(result) != 1:
                raise RuntimeError("POSTGRESQL_SCHEMA_READINESS_FAILED")
            fields = [library.PQgetvalue(result, 0, index).decode() for index in range(4)]
            if not 170000 <= int(fields[0]) < 180000 or fields[2:] != ["t", "t"]:
                raise RuntimeError("POSTGRESQL_TLS_OR_SCHEMA_MISMATCH")
            return {"engine": "postgresql", "version": fields[1], "sslMode": "verify-full", "tlsVerified": True, "schemaReady": True}
        finally:
            library.PQclear(result)
    finally:
        library.PQfinish(connection)
