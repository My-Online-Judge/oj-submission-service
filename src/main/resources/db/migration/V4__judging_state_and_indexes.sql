-- When a worker began the judging now under way (judge-worker's submission.started), in the service's naive local
-- time like created_at. NULL until a worker announces a start.
ALTER TABLE t_submissions ADD COLUMN judging_started_at timestamp(6) without time zone NULL;
-- How many times the submission was rejudged.
ALTER TABLE t_submissions ADD COLUMN rejudge_count integer NOT NULL DEFAULT 0;

-- The unfinished (PENDING 6, JUDGING 7) rows by age: what the reconcile job scans.
CREATE INDEX ix_submissions_unfinished ON t_submissions (created_at) WHERE status IN (6, 7);
-- A submission's judge request in the outbox, by topic and key (the reconcile job's NOT EXISTS).
CREATE INDEX ix_outbox_topic_key ON t_outbox (topic, message_key);
