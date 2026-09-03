# Task Scheduler

A distributed task scheduler built with Spring Boot 4, supporting cron and delayed
jobs, automatic retries with backoff, distributed locking across app instances,
async job execution via RabbitMQ, and a full execution audit trail.

Built as a portfolio project to demonstrate core distributed-systems patterns:
scheduling, locking, message queues, and fault-tolerant retry/dead-letter handling.

## Tech stack

| Concern | Tool |
|---|---|
| Language / framework | Java 17, Spring Boot 4.0.7 |
| Build | Gradle |
| Persistence | PostgreSQL, Spring Data JPA (Hibernate) |
| Cron parsing | cron-utils |
| Distributed locking | ShedLock (JDBC/Postgres-backed) |
| Async messaging | RabbitMQ, Spring AMQP |
| Boilerplate reduction | Lombok |

## Architecture

```
ScheduledJob (DB)
      │
      ▼
JobDispatcher  ──[@Scheduled every 5s, guarded by ShedLock]──┐
      │                                                       │
      │ finds due jobs, publishes to queue                    │ only one node
      ▼                                                       │ dispatches at
  jobs.queue  ────────────────────────────────────────────────┘ a time
      │
      ▼
  JobWorker  ──[@RabbitListener]── JobExecutorRegistry ── JobExecutor (pluggable)
      │
      ├─ success → JobRun(SUCCESS)
      └─ failure → JobRun(RETRYING) → jobs.retry.queue (TTL-based backoff)
                        │
                        └─ after maxRetries exceeded → jobs.dlq.queue, JobRun(DEAD)
```

Every dispatch and execution attempt is logged to `job_run`, giving a full audit
trail per job (`QUEUED → RUNNING → SUCCESS` or `RETRYING × N → DEAD`).

## Core components

- **`ScheduledJob`** — job definitions: name, type (`CRON` / `DELAYED` / `ONE_OFF`),
  cron expression, next run time, payload, executor type, retry/timeout config.
- **`JobRun`** — one row per execution attempt: status, attempt number, worker id,
  timestamps, error message. This is the audit log / monitoring data.
- **`JobDispatcher`** — ticks every 5 seconds, guarded by `@SchedulerLock` so only
  one app instance dispatches on a given tick even when scaled horizontally. Finds
  due jobs, publishes them to RabbitMQ, and recomputes `nextRunAt` for cron jobs
  (via cron-utils) or disables one-off jobs after they fire.
- **`RabbitMQConfig`** — defines a three-queue topology:
  - `jobs.queue` — main work queue, consumed by workers
  - `jobs.retry.queue` — holds failed messages for a TTL (backoff delay), then
    dead-letters them back to `jobs.queue` for redelivery
  - `jobs.dlq.queue` — terminal home for jobs that exhausted all retry attempts
- **`JobExecutor` / `JobExecutorRegistry`** — pluggable strategy pattern for job
  logic. Each job type implements `JobExecutor` and registers under a `key()`;
  the registry auto-collects all Spring-managed `JobExecutor` beans and looks
  them up by the `executorType` string on each `ScheduledJob`.
- **`JobWorker`** — `@RabbitListener` consumer. Looks up and runs the right
  executor, and on failure reads RabbitMQ's `x-death` header to get the *actual*
  redelivery count (rather than trusting a field on the message body, which
  RabbitMQ doesn't update on redelivery). Once the real attempt count reaches
  `maxRetries`, it publishes directly to the DLQ instead of looping forever.
- **`JobController`** — REST API: create jobs, list jobs, view run history.

## Setup

**1. Start infrastructure:**
```bash
docker compose up -d
```
This starts Postgres (`5432`) and RabbitMQ (`5672`, management UI on `15672`,
default guest/guest).

**2. Configure `application.properties`** (adjust to match your DB name/creds):
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/taskSchedularDb
spring.datasource.username=scheduler
spring.datasource.password=scheduler
spring.jpa.hibernate.ddl-auto=update
spring.sql.init.mode=always

spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
spring.rabbitmq.listener.simple.concurrency=5
spring.rabbitmq.listener.simple.default-requeue-rejected=false

server.error.include-message=always
```

**3. Run the app** (via IDE or `./gradlew bootRun`). On first boot, Hibernate
creates `scheduled_jobs` and `job_run`, and `schema.sql` creates the `shedlock`
table ShedLock needs for distributed lock state.

## API

**Create a job**
```bash
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "cron-test",
    "jobType": "CRON",
    "cronExpression": "0 */1 * * * *",
    "nextRunAt": "2026-08-15T08:20:00",
    "executorType": "logging",
    "payload": "{\"msg\":\"cron tick\"}",
    "status": "ACTIVE",
    "maxRetries": 3,
    "timeout": 300
  }'
```

**List jobs**
```bash
GET /api/jobs
```

**View a job's run history**
```bash
GET /api/jobs/{id}/runs
```

## What's been verified end-to-end

- **Cron jobs reschedule correctly** — a job with `0 */1 * * * *` was observed
  firing every 60 seconds, with `next_run_at` advancing each time rather than
  the job being deactivated.
- **One-off/delayed jobs fire once and deactivate** — `status` flips to
  `DISABLED` after execution so they're never picked up again.
- **Retries back off correctly** — failed jobs sit in `jobs.retry.queue` for a
  TTL-based delay before automatic redelivery, rather than retrying instantly.
- **Dead-lettering terminates** — after exceeding `maxRetries` (verified with a
  deliberately-failing test executor), a job is routed to `jobs.dlq.queue`
  exactly once, instead of retrying indefinitely.
- **Distributed lock prevents duplicate dispatch** — `@SchedulerLock` wraps the
  dispatch tick using a Postgres-backed lock table (`shedlock`), so only one
  app instance would dispatch a given tick even when scaled to multiple nodes.

## Known simplifications

Worth naming explicitly — these were deliberate scope decisions for a portfolio
project, not oversights:

- **`job_run` table is missing `@Table(name = "job_runs")`**, so Hibernate falls
  back to the singular default name, inconsistent with `scheduled_jobs`. Fixing
  it requires a table rename since `ddl-auto=update` won't rename existing tables.
- **Single global dispatch lock, not sharded.** `@SchedulerLock` wraps the whole
  dispatch tick, so only one node dispatches at a time. Fine at this scale;
  a higher-throughput system would shard jobs across nodes (e.g. by job ID hash)
  instead of a single lock.
- **No request DTOs on the REST API** — `POST /api/jobs` accepts the JPA entity
  directly, so nothing stops a caller from setting fields like `id` or `status`
  that should be server-controlled. A hardened API would use a separate
  `CreateJobRequest` DTO.
- **`ddl-auto=update`, not migrations** — fine for a portfolio project; a real
  deployment should use Flyway or Liquibase for versioned schema changes.
- **No metrics/dashboard layer** — Actuator is on the classpath, but no custom
  Micrometer counters/timers were added. The `job_run` table itself contains
  everything needed to build one (success/failure counts, durations) if added
  later.

## Possible next steps

- Add Micrometer counters/timers in `JobWorker` and expose via
  `/actuator/prometheus` for a Grafana dashboard.
- Introduce request/response DTOs on the REST layer instead of exposing entities.
- Add Flyway migrations in place of `ddl-auto=update`.
- Write integration tests (e.g. Testcontainers for Postgres + RabbitMQ) covering
  the retry-to-DLQ path and cron rescheduling logic.
