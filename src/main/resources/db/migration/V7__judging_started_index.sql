-- Rows a worker has started, by when: the reconcile job's "is the queue still being worked through" probe
-- (SubmissionTimeouts.failPendingCreatedBefore) asks for any start in the last few minutes.
CREATE INDEX ix_submissions_judging_started ON t_submissions (judging_started_at) WHERE judging_started_at IS NOT NULL;
