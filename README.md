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
judge-server token, problem-service's gRPC address and service token, and the JWKS URI through the compose file.

Events: `submission.requested` (to the workers) and `oj.submission.events` (`SubmissionVerdictRecorded`, to
problem-service's statistics) leave through `t_outbox`; alert `OutboxBacklogStale` fires when a row waits over a
minute.

## Build and test

`oj-common` must be installed first (`./mvnw install` in the sibling `oj-common` repo). Use `./mvnw`
(Maven 3.9.9): oj-common's protobuf plugin needs Maven 3.9.6 or later.

    ./mvnw verify '-Djunit.jupiter.conditions.deactivate=org.testcontainers.*'

Docker builds compile `oj-common` from the named build context:
`docker build --build-context oj-common=../oj-common .`
