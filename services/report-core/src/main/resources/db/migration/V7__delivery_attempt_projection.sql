-- Go owns execution records; this is an authenticated event projection for job views.
CREATE TABLE delivery_attempt (
 delivery_id uuid NOT NULL REFERENCES delivery_outbox(id), number integer NOT NULL CHECK(number BETWEEN 1 AND 6),
 phase text NOT NULL, status text NOT NULL, code text NOT NULL,
 started_at timestamptz NOT NULL, ended_at timestamptz,
 PRIMARY KEY(delivery_id,number)
);
