ALTER TABLE t_submissions ADD COLUMN contest_id uuid;
CREATE INDEX ix_submissions_contest_user ON t_submissions (contest_id, user_id) WHERE contest_id IS NOT NULL;
