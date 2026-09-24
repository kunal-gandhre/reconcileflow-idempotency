<!--
  Copyright 2026 ReconcileFlow
  Author: Kunal Gandhre
  SPDX-License-Identifier: Apache-2.0
  Licensed under the Apache License, Version 2.0; see LICENSE.
  https://www.apache.org/licenses/LICENSE-2.0
-->

# ReconcileFlow

**Repeat delivery. Not the work.**

Redis-backed deduplication for synchronous Kafka consumers, with a Java 21 Spring Boot starter and an idiomatic Go handler wrapper.

[Website](https://kunal-gandhre.github.io/reconcileflow-idempotency/) · [Internal development and testing runbook](docs/internal/DEVELOPMENT.md) · [CI](https://github.com/kunal-gandhre/reconcileflow-idempotency/actions/workflows/ci.yml)

ReconcileFlow claims an event, runs your handler, and remembers successful completion for a bounded period. A completed duplicate skips business work; an event still being processed returns an error so the consumer can retry.

**Status: early preview, `0.1.0-SNAPSHOT`.** Build from source. No Maven Central release, production certification, or exactly-once side-effect guarantee is claimed. Licensed under Apache 2.0.

## Why this exists

A consumer can finish a business operation and receive the same event again. Repeating an email, fulfillment request, or external API call can be costly. A DLQ captures failures; it does not automatically deduplicate successful deliveries.

This library centralizes the bounded deduplication mechanics while leaving Kafka configuration and business-level idempotency in your application. It is not a replacement for Kafka transactions, a transactional inbox, or a payment provider's idempotency key.

## Included today

- Java `@Idempotent` annotation used alongside Spring's `@KafkaListener`.
- Spring Boot auto-configuration, custom-store override, and opt-out property.
- Go middleware independent of your Kafka client.
- Atomic Redis Lua transitions, with separate processing leases and completion retention.
- Owner-checked completion and release, hashed storage keys, and fail-closed store errors.
- Unit tests, real Redis integration tests, Docker Compose, and runnable examples.
- A static website with Java/Go examples and an interactive state-flow illustration.

Not implemented: JDBC store, automatic lease renewal, batch/async listeners, Micrometer metrics, a hosted control plane, or a combined `@IdempotentKafkaListener` annotation.

## State contract

```text
ABSENT ── claim(owner, lease) ──> PROCESSING
                                  │
                      success + valid owner
                                  ↓
                               COMPLETED ── retention expires ──> ABSENT

PROCESSING + repeat delivery → retryable error (do not acknowledge)
PROCESSING + handler failure → owner-checked release → retry
COMPLETED  + repeat delivery → skip handler → normal return
```

Each operation touches one Redis key atomically. Completed records contain `DONE`; active records contain a random ownership token. A stale worker cannot delete or complete a newer worker's claim.

## Quick start — Java

Prerequisites: JDK 21, Maven 3.9+, Docker Compose. Tested dependency baseline: Spring Boot 3.5.6 and Spring Kafka 3.3.10.

```bash
git clone https://github.com/kunal-gandhre/reconcileflow-idempotency.git
cd reconcileflow-idempotency
mvn install
docker compose -p reconcileflow-dev up -d
```

Compose also starts [RedisInsight](http://localhost:5540) and [Kafka UI](http://localhost:8081), preconfigured for the local services. RedisInsight connects to `redis:6379`; Kafka UI uses `kafka:29092` inside Docker. Host applications continue to use `localhost:6379` and `localhost:9092`. All published ports bind to loopback. See the [development runbook](docs/internal/DEVELOPMENT.md) for UI checks.

After local installation, add this dependency to a Spring Boot application:

```xml
<dependency>
  <groupId>com.reconcileflow</groupId>
  <artifactId>spring-boot-starter-kafka-idempotent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Configure Redis and record-based acknowledgment:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    consumer:
      enable-auto-commit: false
    listener:
      ack-mode: record
reconcileflow:
  enabled: true
```

Use both annotations on a public, synchronous `void` Spring bean method:

```java
import com.reconcileflow.idempotent.Idempotent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class OrderConsumer {
    @KafkaListener(topics = "order-events", groupId = "fulfillment")
    @Idempotent(
        key = "#payload",
        namespace = "fulfillment:order-events:v1",
        lease = "60s",
        retention = "24h")
    public void process(String eventId) {
        // Complete synchronous business work here before returning.
    }
}
```

`#payload` is the first argument; `#p0`, `#p1`, etc. reference positional arguments. For a deserialized bean, use `#payload.orderId` with a readable property. A raw `ConsumerRecord` can use `#p0.value` when its value is the event ID. Configure payload deserialization in Spring Kafka normally. Expressions are restricted to read-only data binding; arbitrary type references and method calls are not supported. Null/blank keys and invalid durations fail before processing.

Choose a stable namespace containing the logical consumer, topic and optional business version. Different consumers usually need different namespaces. Use a stable **event ID**, not an order ID if multiple legitimate events exist per order. Defaults are a 60-second lease and 24-hour retention. Durations support Spring's syntax, such as `60s`, `24h`, or `PT1M`.

### Retry and transaction configuration

Do not acknowledge manually inside the handler. Do not swallow handler/store exceptions. Configure retries for busy claims, Redis outages, and uncertain completion; Spring's default error handler can eventually recover a record, so the annotation alone does not guarantee indefinite redelivery.

The demo registers an unlimited fixed-backoff error handler to make this explicit. For a real application, define bounded retries, an observable DLQ/recovery policy, and an operator procedure; invalid configuration must not retry forever unnoticed.

Commit business database work synchronously **inside** the handler, for example by invoking a separate transactional service. An already-active ambient/container transaction is rejected. This preview does not atomically coordinate Redis, Kafka offsets, and a database. Async work, self-invocation, final/private annotated methods, batch payloads, manual acknowledgments and container transactions are outside the supported contract.

### JSON payloads

Raw JSON messages are supported explicitly. Use a stable **string** event ID, not
the entire serialized message: whitespace, property order and other fields do not
change its deduplication identity.

```java
@KafkaListener(topics = "order-json-events", groupId = "fulfillment-json-demo")
@Idempotent(json = true, key = "#payload['eventId']",
    namespace = "fulfillment-json-demo:order-json-events:v1")
public void processJson(String payload) {
    // The full, original JSON is available for business processing.
}
```

Java accepts a raw JSON `String` or `byte[]`. In JSON mode, `#payload` is a parsed
object for key evaluation; `#p0` and the actual handler argument retain the original
value. Nested fields work with `#payload['event']['id']`. For already-deserialized
POJOs or maps, keep the default `json = false` and use the existing SpEL property or
map expression. Jackson parses objects as data without polymorphic type activation.

For Go, pass `idempotent.JSONKey("eventId")` as the extractor to `Wrap`, or
`idempotent.JSONKey("event", "id")` for nested fields. The handler still receives
the original bytes. Try the Redis-backed example with `cd go` then
`go run ./examples/json`.

Example messages (first two represent the same event):

```json
{"eventId":"order-json-1","amount":10}
{"amount":10,"eventId":"order-json-1"}
{"eventId":"order-json-2","amount":20}
```

Each line is a separate message. Malformed JSON, non-object roots, missing/null/blank
IDs, numeric IDs and multiple concatenated JSON documents fail before any Redis
claim or business work. Use unique field names in JSON objects. Reusing an ID with
different business data still skips the later delivery after completion; this is
identity-based deduplication, not payload equality. Configure a recovery/DLQ policy
for invalid messages; the demo's unlimited retry policy will keep retrying them.

### Run the Kafka demo

```bash
mvn package
java -jar examples/order-service/target/order-service-0.1.0-SNAPSHOT.jar
```

In another terminal:

```bash
docker compose -p reconcileflow-dev exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic order-events --partitions 1 --replication-factor 1
docker compose -p reconcileflow-dev exec kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic order-events
```

Type `demo-event-1` twice, then `demo-event-2`. Expect one `Processed order event:` line per unique ID while retention remains active. Use fresh IDs for subsequent runs; Redis completion state persists in the Compose volume.

The same application also listens on `order-json-events`. Create that topic using
the command above with its name substituted, then start the producer for that topic
and paste the three JSON lines from the JSON section. Expect two
`Processed JSON order event:` lines. If you disabled the starter locally, start the
demo with `--reconcileflow.enabled=true` to test deduplication.

## Quick start — Go

Go 1.23+ is required. From the repository:

```bash
cd go
go test ./...
go run ./examples/basic
```

The example connects to Redis on `localhost:6379` and sends three deliveries for two unique event IDs. It is a handler demonstration, not a Kafka consumer implementation. For another local module, use a `replace` directive pointing to this checkout's `go` directory until a versioned release is published.

```go
import (
    "time"
    idempotent "github.com/kunal-gandhre/reconcileflow-idempotency/go"
    "github.com/kunal-gandhre/reconcileflow-idempotency/go/redisstore"
    "github.com/redis/go-redis/v9"
)

client := redis.NewClient(&redis.Options{Addr: "localhost:6379"})
store := redisstore.New(client)
handler, err := idempotent.Wrap(store, idempotent.Config{
    Namespace: "fulfillment:order-events:v1",
    Lease: time.Minute,
    Retention: 24 * time.Hour,
}, extractEventID, processOrder)
if err != nil { return err }
err = handler(ctx, message.Value)
// Commit this message's offset only after nil; otherwise retry it.
```

`extractEventID` is `func([]byte) (string, error)` and `processOrder` is `func(context.Context, []byte) error`. Disable client auto-commit. Process each partition in order, or use an offset tracker that never commits past an unprocessed earlier record. A nil result means either processed successfully or already completed. `ErrBusy` and `ErrLeaseLost` must not be acknowledged as duplicates. Cleanup after a handler error uses a separate three-second context; a panic propagates and leaves the lease to expire.

## Guarantees and limits

| Situation | Behavior / responsibility |
| --- | --- |
| Same key arrives while lease is valid | One claim wins; other deliveries receive a retryable error |
| Same key arrives after completion | Handler skipped until completion retention expires |
| Handler throws | Owner-checked release; original failure propagated |
| Redis unavailable before claim | Handler does not run |
| Lease expires during work | Another worker may start; old worker cannot finalize the new claim |
| Crash after side effect, before `DONE` | Side effect may repeat after lease expiry |
| Redis loses or evicts a record | Deduplication history is lost; processing may repeat |
| Replay beyond retention | Event may run again |

Use a lease longer than the maximum synchronous handler time, but understand that pauses and failures can exceed it. There is no fencing of external business systems or lease renewal. For financial or otherwise irreversible writes, use a unique inbox record committed in the same database transaction as the business change, or pass an idempotency key to the downstream system.

Redis persistence, no-eviction policy, access controls and failover behavior are deployment responsibilities. Compose is a loopback-only development setup, not a production deployment. Hashing keys avoids putting raw IDs in key names; it does not make predictable IDs cryptographically anonymous.

See [Redis ownership-token guidance](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) and [Spring's discussion of external side effects](https://spring.io/blog/2023/10/16/apache-kafkas-exactly-once-semantics-in-spring-cloud-stream-kafka/).

## Repository map

```text
kafka-idempotent-core/                 annotation, aspect, Redis state machine
spring-boot-starter-kafka-idempotent/   auto-configuration
examples/order-service/                runnable Spring Kafka consumer
go/                                   middleware, Redis store, runnable example
website/                              dependency-free static website
docs/internal/DEVELOPMENT.md           internal development and test runbook
.github/workflows/                     CI and GitHub Pages deployment
```

## Develop and test

Run `mvn verify` and, inside `go/`, `go test ./...`. Real Redis tests are opt-in locally with `REDIS_INTEGRATION=true`; CI enables them. The complete testing procedure, expected results, troubleshooting and limitations are in [the internal development README](docs/internal/DEVELOPMENT.md).

For the website (Node.js 22+):

```bash
cd website
npm run dev
# Open http://127.0.0.1:4173
npm run build
```

No npm dependencies or build framework are required. Deploy `website/dist` to a static host. The included GitHub Pages workflow requires repository Settings → Pages → Source: GitHub Actions.

## Contributing

Start with an issue describing the failure scenario. Include a regression test for changes to claim ownership, retries, key calculation or completion. Keep Java and Go state semantics aligned. Never add telemetry containing event IDs or payloads by default.

## Roadmap

Near-term candidates: transactionally coupled JDBC inbox, bounded metrics, lease-renewal design, Kafka integration test automation, and published release artifacts. These are proposals, not available features or promises.
