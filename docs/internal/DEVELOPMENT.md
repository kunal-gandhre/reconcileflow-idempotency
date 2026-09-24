<!--
  Copyright 2026 ReconcileFlow
  Author: Kunal Gandhre
  SPDX-License-Identifier: Apache-2.0
  Licensed under the Apache License, Version 2.0; see LICENSE.
  https://www.apache.org/licenses/LICENSE-2.0
-->

# Internal development README

Maintainer runbook for building, testing, and releasing ReconcileFlow. “Internal” describes the audience: this file is committed to the public repository. Never put credentials, private payloads, or production incident data here.

## Environment

- Java 21, Maven 3.9+, Go 1.23+ (local verification used Go 1.24.2), Node.js 22+.
- Docker Engine with Compose, available ports 6379, 9092, 5540 and 8081.
- Redis 7.4 and Apache Kafka 3.9.1 from the root `compose.yml`.
- Commands below run from the repository root unless stated otherwise.

The libraries deliberately separate an active processing lease from completed-event retention. Tests must never treat a busy claim as a completed duplicate. The business side effect and Redis completion are not one transaction.

## 1. Start test infrastructure

```bash
docker compose -p reconcileflow-dev up -d
docker compose -p reconcileflow-dev ps
docker compose -p reconcileflow-dev exec -T redis redis-cli ping
docker compose -p reconcileflow-dev exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Expected: all four containers running, Redis and Kafka healthy, Redis replies `PONG`, Kafka lists topics without a connection error. Image download completion is not proof of readiness; check the services above. Ports bind only to loopback. Use a separate Compose project for unrelated work.

### RedisInsight and Kafka UI

- Open RedisInsight at http://localhost:5540. The `ReconcileFlow local` connection is preconfigured at `redis:6379`, database 0, without a password for this local setup. Complete any first-run prompts in the UI. Browse `rf:*` keys after running an example; completed events contain `DONE`. GUI settings persist in the separate `redisinsight-data` volume.
- Open Kafbat Kafka UI at http://localhost:8081. Select `ReconcileFlow local`, then inspect brokers, topics, and consumer groups. After running the Kafka example, inspect `order-events` and the `fulfillment-demo` group.
- Kafka UI connects through the internal `kafka:29092` listener; host applications still connect through `localhost:9092`. Port 29092 is only used on the Compose network. Separate advertised listeners let both kinds of client retrieve reachable broker addresses.
- UI images use upstream `latest` tags for local development. Pin a tested version or digest if you need repeatable UI deployments. Configuration follows the official [RedisInsight Docker guide](https://redis.io/docs/latest/operate/redisinsight/install/install-on-docker/), [Redis connection settings](https://redis.io/docs/latest/operate/redisinsight/configuration/), and [Kafbat getting-started guide](https://ui.docs.kafbat.io/overview/getting-started).
- Kafka stores messages and metadata in `kafka-data`. When upgrading an older checkout that stored data in the container filesystem, stop the old broker and back up `/tmp/kafka-logs` before recreating it; restore that backup into the new volume with ownership for `appuser`. Ordinary future container recreations retain this named volume.

UI smoke-check commands (PowerShell):

```powershell
docker compose -p reconcileflow-dev config --quiet
docker compose -p reconcileflow-dev up -d
docker compose -p reconcileflow-dev ps
(Invoke-WebRequest -UseBasicParsing http://localhost:5540/api/health/).StatusCode
(Invoke-WebRequest -UseBasicParsing http://localhost:8081).StatusCode
Invoke-RestMethod 'http://localhost:8081/api/clusters'
Invoke-RestMethod 'http://localhost:8081/api/clusters/ReconcileFlow%20local/topics'
```

Expected: valid Compose configuration, HTTP 200 from both UIs, and Kafka cluster/topic responses without connection errors. Inspect the preconfigured Redis database in the UI; an HTTP health response alone does not prove its database connection works. These UIs can modify local data; use inspection views for smoke checks.

### Reset all test data

For a fresh test environment, stop host-side producers and consumers, then run
`./scripts/reset-test-data.ps1` from PowerShell. This deletes this project's Kafka
messages, topics and consumer offsets, every Redis database record, and RedisInsight
settings, then starts the services and waits for Redis/Kafka health checks. Recreate
`order-events` using the example command below before testing again. RedisInsight
may show its first-run screen again. Ordinary `docker compose up -d` preserves data.

Equivalent commands on other shells:

```bash
docker compose -p reconcileflow-dev down --volumes
docker compose -p reconcileflow-dev up -d --wait --wait-timeout 120
```

Verify the reset before starting applications: `docker compose -p reconcileflow-dev
exec -T redis redis-cli INFO keyspace` should list no populated databases, and
`docker compose -p reconcileflow-dev exec -T kafka /opt/kafka/bin/kafka-topics.sh
--bootstrap-server localhost:9092 --list` should show no application topics.

## 2. Java unit and Spring wiring tests

```bash
mvn -B -ntp verify
```

This compiles all three Java modules and runs:

- `IdempotentAspectTest`: new event executes; completed duplicate skips; busy claim throws; unavailable Redis fails closed; failure releases only the owned claim; cleanup errors preserve the original failure; completion after lease loss throws; invalid keys and async return types are rejected; ambient transactions are rejected.
- `AutoConfigurationTest`: default Redis store/aspect wiring; custom store override; disabled configuration; startup fails when no store exists.

Without the opt-in environment variable, real Redis tests are **skipped**, not passed. Read each module's `target/surefire-reports/*.txt` for test counts. The missing-store test intentionally logs a Spring startup warning. Mockito currently emits Java-agent warnings on JDK 21; these are not test failures.

## 3. Java tests against real Redis

PowerShell:

```powershell
$env:REDIS_INTEGRATION = 'true'
mvn -B -ntp verify
Remove-Item Env:REDIS_INTEGRATION
```

Bash:

```bash
REDIS_INTEGRATION=true mvn -B -ntp verify
```

`RedisStoreIntegrationTest` verifies:

1. New → busy → completed transitions, including attempts by the wrong owner.
2. An expired worker cannot release or complete a replacement worker's claim.
3. Exactly one of 32 concurrent attempts acquires a still-valid lease.

Tests use unique `rf:test:` keys with short expirations and never run `FLUSHDB`. `REDIS_PORT` optionally overrides 6379. These checks run real Lua scripts through Spring Data Redis, not a mock implementation.

## 4. Go validation

```bash
cd go
go mod download
go vet ./...
go test -count=1 -v ./...
REDIS_INTEGRATION=true go test -race -count=1 -v ./...
```

On PowerShell, set `$env:REDIS_INTEGRATION = 'true'` before the test command and remove it afterwards. `go test -race` requires a supported C toolchain on Windows; use Linux CI or the official Go Docker image if it is unavailable locally. Do not report an ordinary test run as a race-detector pass.

- Middleware tests cover fresh, completed, busy, and lease-lost outcomes; business failure plus cleanup failure; unavailable storage; invalid configuration.
- `redisstore` tests run real Redis ownership transitions, expiry and 32-way concurrent claims. They skip unless `REDIS_INTEGRATION=true`.
- `go vet` checks common code defects independently of test execution.

The Go demo (`go run ./examples/basic`) exercises Redis with three handler deliveries and two unique IDs. It does not exercise a Kafka client or offset commits.

The local race run used this PowerShell command from the repository root (the container shares only the test Redis network namespace):

```powershell
docker run --rm --network container:reconcileflow-dev-redis-1 `
  --mount "type=bind,source=$((Get-Location).Path)/go,target=/src" `
  -w /src -e REDIS_INTEGRATION=true golang:1.24 `
  go test -race -count=1 ./...
```

## 5. Kafka → Spring proxy → Redis end-to-end smoke test

Build the application:

```bash
mvn -B -ntp package
java -jar examples/order-service/target/order-service-0.1.0-SNAPSHOT.jar
```

In a second terminal:

```bash
docker compose -p reconcileflow-dev exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic order-events --partitions 1 --replication-factor 1
docker compose -p reconcileflow-dev exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-events
```

Enter a fresh identifier twice and a second fresh identifier once. Example: `smoke-20260924-a`, `smoke-20260924-a`, `smoke-20260924-b`. Exit the producer with Ctrl+C.

Expected: exactly one `Processed order event:` log for each unique identifier. Then inspect committed offsets:

```bash
docker compose -p reconcileflow-dev exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group fulfillment-demo
```

Expected: zero lag after the consumer has processed all three deliveries. Repeat a completed identifier and verify the business log count remains unchanged while the committed offset advances. Restart the consumer and submit that identifier again to check Redis-backed retention across process restart. Use fresh IDs for a new smoke run; do not wipe the persistent Redis volume to make an assertion pass.

This validates actual `@KafkaListener` invocation through the Spring AOP proxy and auto-configuration, Redis connectivity, duplicate suppression, and record acknowledgments. It does **not** simulate a crash between a real business database commit and Redis completion.

### JSON payload smoke test

Build with `mvn -B -ntp verify` and start the example with
`java -jar examples/order-service/target/order-service-0.1.0-SNAPSHOT.jar --reconcileflow.enabled=true`.
Use `order-json-events` and group `fulfillment-json-demo`; the plain-text listener
on `order-events` remains available. Ensure no older consumer for the JSON group
is running, since it could take the test records.

In PowerShell, send three distinct lines (parenthesize concatenated array elements):

```powershell
$eventId = 'json-smoke-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$messages = @(
    ('{"eventId":"' + $eventId + '","amount":10}')
    ('{"amount":10,"eventId":"' + $eventId + '"}')
    ('{"eventId":"' + $eventId + '-second","amount":20}')
)
$messages | docker compose -p reconcileflow-dev exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-json-events
docker compose -p reconcileflow-dev exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group fulfillment-json-demo
```

Expected: exactly two `Processed JSON order event:` lines for these IDs; the second
delivery skips business work despite different field order. All three deliveries
advance the committed offset, with zero lag. The JSON Go example
(`cd go; go run ./examples/json`) also prints exactly two processed messages.
JSON unit tests cover byte/string delivery, nested paths, identity independent of
serialization, original handler payload preservation and invalid input before
store access. Malformed input is tested without injecting poison records into a
shared Kafka topic: this demo retries runtime failures indefinitely.

## 6. Website build and browser checks

```bash
cd website
npm run build
npm run dev
```

Open `http://127.0.0.1:4173`. There is no npm install step: the website uses Node's standard library and browser-native HTML/CSS/JavaScript.

The flow under test is: page loads → a user selects a quickstart or steps through delivery → the displayed code/state changes as expected.

Manual/browser automation procedure:

1. Check page title, main heading, navigation, diagram, three process steps, quickstart, footer, and absence of a blank page/error overlay.
2. Select Go, confirm Go code and the Go guide link; select Java, confirm Java code and its guide link.
3. Use ArrowLeft/ArrowRight and Home/End on tabs; verify focus and `aria-selected` follow the active tab.
4. Click Copy; verify the clipboard matches the selected snippet and the success message appears. If clipboard permission is denied, verify the fallback instruction appears.
5. Click Explore the flow three times: new claim, busy/retry, completed/skip. A fourth click restarts the sequence. This is a labeled browser illustration, not a backend integration.
6. Expand “Understand the boundaries”; verify the full limitation text becomes visible.
7. Test in desktop (1440×1000) and mobile (390×844) viewports. Check overflow, readable code, heading wrapping, tap targets and all anchor links.
8. Read browser console errors/warnings. Inspect screenshots against the design concept, accounting for deliberate corrections to inaccurate exactly-once wording.

Use the connected browser first. Keep screenshots and scratch scripts outside committed source, or in ignored local folders. Save the actual test results below; a successful static build alone is not proof the browser interactions work.

## 7. CI and deployment

`.github/workflows/ci.yml` runs Java with Redis, Go with Redis and the race detector, and the static website build on pushes and pull requests. `.github/workflows/pages.yml` builds and deploys `website/dist` on relevant main-branch pushes or manual dispatch. Configure GitHub Pages to use GitHub Actions before deployment. CI is only “passing” after inspecting the actual workflow result, not merely because a workflow file exists.

Before publishing: inspect `git diff --check`, staged paths, license, README links and ignore rules. Never commit `.tools`, test logs, Redis data, build outputs, downloaded toolchains or secrets. This is a source preview; do not add Maven Central badges or release claims before artifact publication.

## 8. Cleanup and troubleshooting

- Stop the sample consumer and website using Ctrl+C in their terminals.
- `docker compose -p reconcileflow-dev stop` stops this project's containers and preserves their state.
- `docker compose -p reconcileflow-dev down` removes this project's containers/network while retaining the Redis, RedisInsight and Kafka named volumes. Do not use `down -v` unless intentionally discarding test history.
- Docker pipe access denied: retry from a terminal with access to the running Docker engine; do not weaken the engine's security.
- Maven/Go network denied: dependency downloads need network access. Use the normal approved package sources; do not disable TLS verification.
- Redis connection refused: verify container health, host and port before running integration tests.
- No repeated demo output: completed state may already exist for that ID. Use a fresh event ID.
- Busy forever: inspect handler behavior and lease duration; never delete a live worker's claim blindly.

## Verification record — 2026-09-24

| Check | Result |
| --- | --- |
| Java unit, Redis integration, Spring wiring | Passed: 16 tests total, no failures or skips with Redis enabled |
| Go middleware and real Redis tests | Passed: 7 top-level tests (plus 4 outcome subtests); `go vet` passed |
| Go race detector | Passed in official `golang:1.24` Linux container; Windows lacked cgo |
| Kafka consumer duplicate/offset/restart smoke | Passed: deliveries a,a,b produced two handler logs; offset 3/3, lag 0. After restart, a produced no new handler log; offset 4/4, lag 0 |
| Website build and browser desktop/mobile checks | Passed: Node build; Edge at 1440×1000 and 390×844; tabs, keyboard switching, copy, three-state simulation, disclosure, no console errors; fixed mobile horizontal overflow |
| GitHub CI | Passed: Java, Go race tests, and website build in [run 36005367573](https://github.com/kunal-gandhre/reconcileflow-idempotency/actions/runs/36005367573) |
| GitHub Pages | Passed: [deployment 36005367539](https://github.com/kunal-gandhre/reconcileflow-idempotency/actions/runs/36005367539). Live page verified at https://kunal-gandhre.github.io/reconcileflow-idempotency/ |

The initial Pages run failed because the repository's Pages source had not yet been configured. Setting Source to GitHub Actions and pushing the website refinements resolved it. Local screenshots were inspected at desktop and mobile sizes; final deployed DOM and asset loading were checked separately. A later full-page screenshot attempt timed out in the browser automation layer; this did not affect site loading or the earlier visual checks.

Remaining coverage limits: no Redis failover/eviction fault injection, no business database transaction crash test, no Kubernetes rebalance test, no automatic lease renewal, no throughput benchmark, and no real Go Kafka consumer integration. Extend these before making stronger production claims.

### UI addition verification — 2026-09-24

Compose validation passed and all four services started. Redis and Kafka health checks passed; Redis returned `PONG`; host-listener Kafka topic listing succeeded. Both UI URLs returned HTTP 200. Kafbat's cluster API reported `ONLINE` with one broker, and its topics API listed `order-events`. Existing Kafka data was copied from the stopped container into `kafka-data` before recreation; the topic and consumer offsets remained available. The current sample group has no active consumer and lag 6, so this inspection is not a fresh end-to-end processing test.

RedisInsight's health endpoint returned 200, and browser inspection reached its first-run EULA/privacy screen. Database browsing remains unverified until the user completes those prompts; no terms or telemetry choices were submitted automatically. The Compose environment supplies the local Redis connection. If no connection appears after onboarding, add `redis:6379` manually with alias `ReconcileFlow local`.

The accompanying comment/licensing changes passed 16 Java tests with Redis enabled, Go tests and `go vet`, and the static site build before the Compose UI additions. See [LICENSING.md](LICENSING.md) for attribution rules and detailed checks.

### Fresh-data reset verification — 2026-09-24

Executed `scripts/reset-test-data.ps1` against `reconcileflow-dev`. The first run
exposed a fresh-volume permissions error at `/tmp/kafka-logs`; Compose now mounts
Kafka data at the image-prepared `/var/lib/kafka/data` directory. A second full
reset passed and all four services started, with Redis and Kafka healthy.
`redis-cli INFO keyspace` reported no populated databases. An already-running host
consumer recreated `order-events` and `fulfillment-demo`, but the topic end offset
was 0 and the group had no committed offset: previous messages and offsets were
cleared. Stop host applications first if you also want no topics or groups to exist.

### JSON payload verification — 2026-09-24

- `REDIS_INTEGRATION=true` plus `mvn -B -ntp verify`: 32 tests passed, no failures or skips (16 new JSON cases).
- Go `go vet ./...` and `REDIS_INTEGRATION=true go test -count=1 ./...`: passed, including JSON extraction/wrapper tests and real Redis tests.
- Go `go run ./examples/json`: three deliveries produced two processed JSON lines against Redis.
- Java Kafka smoke: three valid JSON messages with two IDs produced two handler lines; group offset 4/4, lag 0. Offset 0 contained a malformed document from an initial shell-array construction error; it was rejected and retried, then explicitly skipped while the test consumer was stopped. The corrected three-record run consumed offsets 1–3. Do not count that manual skip as successful processing.
- The test used `--reconcileflow.enabled=true`; local disabled settings were preserved. Only the test consumer started for this verification was stopped afterwards.
