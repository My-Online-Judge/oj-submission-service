-- JavaScript and PHP are judged again. V3 took them off the list because the qduoj image has neither node nor php;
-- the Go sandbox image ships both, so they can be judged again with the run commands V2 gave them.
--
-- This must ship with the Go-sandbox cut-over (the compose change in judge-deployment), never before it: on the qduoj
-- image these two languages would be offered again and every submission in them would fail to run.
-- Rollback, documented in judge-deployment's README:
--   UPDATE t_languages SET is_disabled = true WHERE identifier IN ('javascript','php');
UPDATE t_languages SET is_disabled = false, updated_at = now() WHERE identifier IN ('javascript', 'php');
