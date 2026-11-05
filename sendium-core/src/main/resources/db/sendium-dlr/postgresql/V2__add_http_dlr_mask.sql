ALTER TABLE sendium_dlr.dlr_message
    ADD COLUMN dlr_mask INTEGER NOT NULL DEFAULT 3 CHECK (dlr_mask BETWEEN 0 AND 3);
