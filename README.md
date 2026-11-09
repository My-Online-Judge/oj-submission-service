# oj-submission-service

Submissions for My Online Judge: accepting a submission (cooldown, the problem's judge spec over gRPC from
problem-service), dispatching it to the judge workers (`submission.requested`, through a transactional outbox),
recording verdicts (`submission.judged`, consumer group `judge-api-results`), streaming them to the browser
(SSE, fanned out over Redis), the reconcile job for stuck submissions, the language list and the judge-server
registry. It is what remained of the judge-api monolith; this repo carries judge-api's history, and became a
service of its own with its own database in sub-project 3b.

| Port | Purpose |
|---|---|
| 8000 | API — the api-gateway routes `/api/v1/{submissions,languages,judge-servers}/**` here; the sandboxes post their heartbeat to `/api/judge_server_heartbeat` |
| 8081 | actuator: `/actuator/health`, `/actuator/prometheus` (dev/prod profiles) |

Configuration comes from `judge-deployment/.env.submission` (see `.env.submission.example` there): its own
Postgres (`submission-db`, schema by Flyway `V1` = the live tables, `V2` = the languages), plus Kafka, Redis, the
judge-server token and problem-service's gRPC address through the compose file. Under the `tls` profile the gRPC
calls use mutual TLS: this service's certificate (CN `submission-service`) replaced the service token.

Events: `submission.requested` (to the workers) and `oj.submission.events` (`SubmissionVerdictRecorded`, to
problem-service's statistics) leave through `t_outbox`; alert `OutboxBacklogStale` fires when a row waits over a
minute. Every `SubmissionVerdictRecorded`, the reconcile job's SYSTEM_ERROR included, carries the submission's
generation (`rejudgeCount`, oj-common 0.10.0) the verdict was stored under: the relay's passes can send two verdicts
of one submission out of order, and problem-service keeps the newer one by it.

Live stream (`GET /api/v1/submissions/{id}/stream`, SSE): while a submission is judged the stream carries a `started`
event (`{"status": 7}`, from the worker's `submission.started`, consumer group `submission-started`; also sent at
subscribe time when the row is already JUDGING) and one `progress` event per judged case (`{testCase, result, error,
cpuTime, realTime, memory, total}`, from the worker's `submission.progress`, group `submission-progress`; never the
program's output), then the single `verdict` event with the full `SubmissionResponseDto`, and completes. Starts and
progress are advisory: they are not stored, they are fanned out across instances over the Redis channel `oj.judging`
(the verdict over `oj.verdicts`), and a lost one leaves only a gap the verdict fills. A record of either topic the
consumer gives up on is dropped, not dead-lettered.

## Build and test

`oj-common` must be installed first (`./mvnw install` in the sibling `oj-common` repo). Use `./mvnw`
(Maven 3.9.9): oj-common's protobuf plugin needs Maven 3.9.6 or later.

    ./mvnw verify '-Djunit.jupiter.conditions.deactivate=org.testcontainers.*'

Docker builds compile `oj-common` from the named build context:
`docker build --build-context oj-common=../oj-common .`
