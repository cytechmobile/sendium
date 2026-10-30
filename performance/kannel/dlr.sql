-- Kannel's upstream PostgreSQL field mapping; provider ID is a string, not a SQL timestamp.
-- Kannel 1.4.5 uses oid to remove/update one row. PostgreSQL 12+ no longer
-- provides table OIDs, so supply an explicit generated identity without changing Kannel's SQL.
CREATE TABLE dlr (
    oid bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    smsc text NOT NULL,
    ts text NOT NULL,
    source text NOT NULL,
    destination text NOT NULL,
    service text NOT NULL,
    url text NOT NULL,
    mask integer NOT NULL,
    status integer NOT NULL,
    boxc text NOT NULL,
    PRIMARY KEY (smsc, ts, destination)
);
