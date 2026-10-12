CREATE TABLE delivery_attempt (
 task_id uuid NOT NULL REFERENCES delivery_task(id), number integer NOT NULL CHECK(number BETWEEN 1 AND 6),
 phase text NOT NULL CHECK(phase IN ('UPLOAD','RECONCILE')),
 status text NOT NULL CHECK(status IN ('STARTED','RETRYING','VERIFIED','FAILED','INTERRUPTED')),
 code text NOT NULL DEFAULT 'NONE', started_at timestamptz NOT NULL DEFAULT now(), ended_at timestamptz,
 PRIMARY KEY(task_id,number)
);
