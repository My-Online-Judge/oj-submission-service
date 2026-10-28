-- The outbox relay sends outside a database transaction (it no longer holds row locks while it waits on Kafka), so a row
-- needs a lease to keep two instances from sending it twice, and a failure count so one record the broker refuses for good
-- cannot stay at the head of the queue for ever.
-- claimed_until: the instance sending the row owns it until then; a crashed instance's rows are free again afterwards.
-- attempts:      how many times the broker refused the row for good (too large, not authorised); a failure that may heal
--                (Kafka down, a timeout) is not counted.
-- dead_at:       set when attempts reaches the limit; a dead row is never sent again and raises an alert.
ALTER TABLE t_outbox ADD COLUMN claimed_until timestamp(6) without time zone;
ALTER TABLE t_outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0;
ALTER TABLE t_outbox ADD COLUMN dead_at timestamp(6) without time zone;
