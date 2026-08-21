CREATE SCHEMA IF NOT EXISTS sendium_dlr;

CREATE TABLE sendium_dlr.dlr_message (
    gateway_message_id UUID PRIMARY KEY,
    account_id TEXT,
    system_id TEXT,
    source_address TEXT,
    destination_address TEXT,
    provider_name TEXT,
    provider_message_id TEXT,
    forward_dlr_url TEXT,
    reassembled_parts TEXT[],
    provider_status TEXT NOT NULL,
    dlr_state INTEGER,
    error_code TEXT,
    delivery_channel TEXT NOT NULL DEFAULT 'NONE',
    delivery_status TEXT NOT NULL DEFAULT 'WAITING_PROVIDER',
    delivery_attempt_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    last_delivery_result TEXT,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dlr_message_provider_pair_check
        CHECK ((provider_name IS NULL) = (provider_message_id IS NULL)),
    CONSTRAINT dlr_message_provider_name_not_blank
        CHECK (provider_name IS NULL OR provider_name !~ '^[[:space:]]*$'),
    CONSTRAINT dlr_message_provider_message_id_not_blank
        CHECK (provider_message_id IS NULL OR provider_message_id !~ '^[[:space:]]*$'),
    CONSTRAINT dlr_message_provider_status_check
        CHECK (provider_status IN ('ACCEPTED', 'SENT', 'DELIVERED', 'FAILED')),
    CONSTRAINT dlr_message_delivery_channel_check
        CHECK (delivery_channel IN ('NONE', 'HTTP', 'SMPP')),
    CONSTRAINT dlr_message_delivery_status_check
        CHECK (delivery_status IN ('WAITING_PROVIDER', 'PENDING', 'FAILED')),
    CONSTRAINT dlr_message_delivery_attempt_count_check
        CHECK (delivery_attempt_count >= 0),
    CONSTRAINT dlr_message_http_url_check
        CHECK (delivery_channel <> 'HTTP' OR
               (forward_dlr_url IS NOT NULL AND forward_dlr_url !~ '^[[:space:]]*$')),
    CONSTRAINT dlr_message_smpp_system_id_check
        CHECK (delivery_channel <> 'SMPP' OR
               (system_id IS NOT NULL AND system_id !~ '^[[:space:]]*$'))
);

CREATE INDEX dlr_message_created_at_idx
    ON sendium_dlr.dlr_message (created_at);

CREATE INDEX dlr_message_provider_message_id_idx
    ON sendium_dlr.dlr_message (provider_name, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

CREATE INDEX dlr_message_http_due_idx
    ON sendium_dlr.dlr_message (next_attempt_at)
    WHERE delivery_channel = 'HTTP' AND delivery_status = 'PENDING';

CREATE INDEX dlr_message_smpp_replay_idx
    ON sendium_dlr.dlr_message (system_id, resolved_at)
    WHERE delivery_channel = 'SMPP' AND delivery_status = 'PENDING';

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
        REFERENCES sendium_dlr.dlr_message (gateway_message_id)
        ON DELETE CASCADE
);

CREATE INDEX provider_correlation_created_at_idx
    ON sendium_dlr.provider_correlation (created_at);

CREATE INDEX provider_correlation_gateway_message_idx
    ON sendium_dlr.provider_correlation (gateway_message_id);
