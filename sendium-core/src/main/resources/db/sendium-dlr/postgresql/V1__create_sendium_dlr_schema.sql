CREATE SCHEMA IF NOT EXISTS sendium_dlr;

CREATE TABLE sendium_dlr.tracked_message (
    gateway_message_id UUID PRIMARY KEY,
    account_id TEXT,
    system_id TEXT,
    source_address TEXT,
    destination_address TEXT,
    provider_name TEXT,
    provider_message_id TEXT,
    forward_dlr_url TEXT,
    reassembled_parts TEXT[],
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT tracked_message_provider_pair_check
        CHECK ((provider_name IS NULL) = (provider_message_id IS NULL)),
    CONSTRAINT tracked_message_provider_name_not_blank
        CHECK (provider_name IS NULL OR provider_name !~ '^[[:space:]]*$'),
    CONSTRAINT tracked_message_provider_message_id_not_blank
        CHECK (provider_message_id IS NULL OR provider_message_id !~ '^[[:space:]]*$'),
    CONSTRAINT tracked_message_status_check
        CHECK (status IN ('ACCEPTED', 'SENT', 'DELIVERED', 'FAILED'))
);

CREATE INDEX tracked_message_created_at_idx
    ON sendium_dlr.tracked_message (created_at);

CREATE INDEX tracked_message_provider_message_id_idx
    ON sendium_dlr.tracked_message (provider_name, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE TABLE sendium_dlr.provider_correlation (
    provider_name TEXT NOT NULL,
    provider_message_id TEXT NOT NULL,
    gateway_message_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_name, provider_message_id),
    CONSTRAINT provider_correlation_provider_name_not_blank
        CHECK (provider_name !~ '^[[:space:]]*$'),
    CONSTRAINT provider_correlation_provider_message_id_not_blank
        CHECK (provider_message_id !~ '^[[:space:]]*$'),
    CONSTRAINT provider_correlation_message_fk
        FOREIGN KEY (gateway_message_id)
        REFERENCES sendium_dlr.tracked_message (gateway_message_id)
        ON DELETE CASCADE
);

CREATE INDEX provider_correlation_created_at_idx
    ON sendium_dlr.provider_correlation (created_at);

CREATE INDEX provider_correlation_gateway_message_idx
    ON sendium_dlr.provider_correlation (gateway_message_id);

CREATE TABLE sendium_dlr.unpushed_dlr (
    dlr_key TEXT PRIMARY KEY,
    system_id TEXT NOT NULL,
    account_id TEXT,
    source_address TEXT,
    destination_address TEXT,
    serial TEXT,
    message_id INTEGER NOT NULL,
    dlr_state INTEGER NOT NULL,
    error_code TEXT,
    acked BOOLEAN NOT NULL,
    priority INTEGER NOT NULL,
    reassembled_parts TEXT[],
    generation_id UUID NOT NULL DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unpushed_dlr_system_id_not_blank
        CHECK (system_id !~ '^[[:space:]]*$')
);

CREATE INDEX unpushed_dlr_system_created_at_idx
    ON sendium_dlr.unpushed_dlr (system_id, created_at);

CREATE INDEX unpushed_dlr_created_at_idx
    ON sendium_dlr.unpushed_dlr (created_at);
