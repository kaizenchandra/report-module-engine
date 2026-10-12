CREATE TABLE IF NOT EXISTS delivery_task (
 id uuid PRIMARY KEY, fingerprint char(64) NOT NULL, payload jsonb NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','VERIFIED','FAILED')),
 attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(),
 receipt jsonb, acknowledged boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS delivery_ready ON delivery_task(next_attempt) WHERE NOT acknowledged;
