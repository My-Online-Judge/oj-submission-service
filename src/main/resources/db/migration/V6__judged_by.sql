-- The worker (the pod, under Kubernetes) that judged the submission: an audit trail for a worker with a bad disk or
-- sandbox. NULL for a verdict from a worker that does not report it and for every submission judged before this column.
ALTER TABLE t_submissions ADD COLUMN judged_by varchar(255);
