CREATE TABLE cloud_control (
 id uuid PRIMARY KEY, fingerprint char(64) NOT NULL, payload jsonb NOT NULL,
 attempts integer NOT NULL DEFAULT 0, next_attempt timestamptz NOT NULL DEFAULT now(),
 result jsonb, acknowledged boolean NOT NULL DEFAULT false,
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cloud_control_ready ON cloud_control(next_attempt) WHERE NOT acknowledged;
