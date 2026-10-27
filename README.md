# BeanGate

*a Java-native AI gateway, brewed with Spring Boot*

One OpenAI-compatible endpoint in front of many model providers: authentication, routing, streaming,
token metering and rate limiting. Clients keep their existing OpenAI SDK and only change `base_url`.

## Status

| Area | State |
|---|---|
| `POST /v1/chat/completions` (streaming and non-streaming) | done |
| Unknown-field preservation (no DTO binding) | done |
| `GET /v1/models`, filtered by key whitelist | done |
| API keys: `sk-gw-*`, HMAC-SHA256 storage, show-once | done |
| Provider / route management, priority-based failover | done |
| SSE frame passthrough, model rewrite, usage interception | done |
| Client disconnect → upstream cancellation | done |
| Anthropic adapter (non-streaming, text-only) | done |
| Redis + Lua rate limiting (RPM exact, TPM approximate) | done |
| Async usage persistence (bounded queue + batch insert) | done |
| Micrometer / Prometheus metrics | done |
| Admin UI, semantic cache, billing, circuit breaker | v2 |

## Flow

```
OpenAI SDK / curl
   │  Authorization: Bearer sk-gw-...
   ▼
[Spring MVC + virtual threads]
   ├─ ApiKeyAuthFilter      HMAC lookup, 60s local cache
   ├─ rate limiter          Redis + Lua, fail-open
   ├─ router                public model → ordered routes (30s cache)
   ▼
[WebClient / Reactor Netty]
   ├─ non-streaming  → JSON tree passthrough, model rewrite, usage record
   └─ streaming      → SSE frame rewrite, usage chunk interception, cancel on disconnect
   ▼
DeepSeek / OpenAI / Ollama / Anthropic
   │
   └─ usage → bounded queue → batch INSERT → MySQL
```

## Run

Requirements: JDK 21. Docker only for the optional MySQL/Redis profile.

```bash
./mvnw spring-boot:run
```

The default `local` profile uses in-memory H2 and needs no external services; rate limiting is off
there because no Redis is assumed. It listens on **18080** (8080/8081 are used by other projects on
this machine; override with `SERVER_PORT`). Swagger UI:
<http://localhost:18080/swagger-ui.html>.

With MySQL + Redis:

```bash
docker compose up -d mysql redis
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

Compose maps MySQL to **13306** and Redis to **16379** because 3306/6379 are already taken on this
machine. Flyway creates the schema on first start.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `18080` | HTTP port |
| `BEANGATE_KEY_PEPPER` | dev value | HMAC pepper for API key hashes |
| `BEANGATE_ENCRYPTION_KEY` | empty → insecure dev key + warning | base64 of 32 bytes, encrypts provider credentials |
| `BEANGATE_ADMIN_TOKEN` | `dev-admin-token` | required in `X-Admin-Token` for `/admin/**` |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `16379` | rate limiter |
| `MYSQL_HOST` / `MYSQL_PORT` | `localhost` / `13306` | MySQL profile |

Change the three `BEANGATE_*` secrets before running anywhere real.

## Five-minute walkthrough

```bash
BASE=http://localhost:18080
ADMIN='X-Admin-Token: dev-admin-token'
JSON='Content-Type: application/json'

# 1. a provider (OpenAI-compatible: DeepSeek, OpenAI, Ollama, …)
curl -s -X POST "$BASE/admin/providers" -H "$ADMIN" -H "$JSON" -d '{
  "name": "deepseek",
  "protocol": "OPENAI_COMPAT",
  "baseUrl": "https://api.deepseek.com/v1",
  "apiKey": "sk-your-deepseek-key",
  "priority": 10
}'

# 2. map a public model to it; add a second route with higher priority for failover
curl -s -X POST "$BASE/admin/routes" -H "$ADMIN" -H "$JSON" -d '{
  "publicModel": "deepseek-chat",
  "providerId": 1,
  "upstreamModel": "deepseek-chat",
  "priority": 10
}'

# 3. mint a gateway key — the plaintext key is returned only once
curl -s -X POST "$BASE/admin/keys" -H "$ADMIN" -H "$JSON" -d '{
  "name": "my-app",
  "allowedModels": ["deepseek-chat"],
  "rpmLimit": 60
}'

# 4. call it exactly like OpenAI
curl -s -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer sk-gw-..." -H "$JSON" \
  -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"hello"}]}'

# 5. streaming: same request with stream=true; -N disables curl buffering
curl -sN -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer sk-gw-..." -H "$JSON" \
  -d '{"model":"deepseek-chat","stream":true,"messages":[{"role":"user","content":"hello"}]}'
```

## Design decisions

Full write-ups live in [`docs/adr/`](docs/adr/):

- **0001** Spring MVC + WebClient + virtual threads instead of WebFlux.
- **0002** Frame-level SSE passthrough: frames are parsed because `model` must be rewritten and usage
  chunks must be identified; comments, unknown fields and `[DONE]` are preserved. `SseEmitter` was
  rejected in favour of writing to the servlet output stream.
- **0003** JSON trees, never typed DTOs: the OpenAI schema only grows, and DTOs drop what they do not
  know about.
- **0004** API keys use HMAC-SHA256, not BCrypt: 256-bit random keys need an indexed fast hash, and
  BCrypt's salt makes lookup impossible.
- **0005** Routes are rows with `priority` and their own `upstream_model`, not a single
  `fallback_provider_id` column.
- **0006** TPM is approximate: tokens are counted after completion, because completion length is
  unknown before forwarding. RPM is exact.
- **0007** No `@Transactional` on cached read paths — the interceptor borrows a JDBC connection even on
  a cache hit, which cost ~6 ms per request before the fix.
- **0008** Flyway owns the MySQL schema; Hibernate DDL owns H2 for local runs and tests.

## Rate limiting

- **RPM** — exact, sorted-set sliding window in Lua, one member per admitted request.
- **TPM** — approximate, two weighted minute buckets, fed by the async usage writer after each call.
  A burst can overshoot by the in-flight completions; RPM still bounds request rate.
- Rejections are gateway `429` errors with `Retry-After`, returned before any upstream call.
- If Redis is unreachable the limiter **fails open**, increments a metric, and logs at most once a
  minute. Disable it entirely with `beangate.rate-limit.enabled=false`.

## Metrics

`GET /actuator/prometheus` exposes:

| Metric | Tags |
|---|---|
| `beangate_requests_total` | `model`, `provider`, `stream`, `status` |
| `beangate_request_latency_seconds` | `stream` (histogram) |
| `beangate_ttft_seconds` | histogram, streaming only |
| `beangate_tokens_total` | `type=prompt|completion` |
| `beangate_errors_total` | `code`, `provider` |
| `beangate_usage_dropped_total` | queue overflow or repeated DB failure |
| `beangate_ratelimit_rejected_total` / `beangate_ratelimit_redis_failures_total` | — |

Tags are limited to bounded dimensions; request ids and key ids are deliberately not labels.

## Benchmark

Method: same machine, Node.js client with keep-alive, 200 interleaved warm-up requests, then 1,000
sequential requests per path. Upstream is a local fake OpenAI server; the gateway runs the `mysql`
profile (MySQL and Redis in Docker). Sequential overhead is the difference between the two columns.

| | p50 | p95 | p99 |
|---|---|---|---|
| direct to upstream | 0.17 ms | 0.34 ms | 0.52 ms |
| through BeanGate | 2.49 ms | 4.17 ms | 5.14 ms |
| **added by the gateway** | **2.32 ms** | **3.82 ms** | **4.63 ms** |

Throughput at 10 concurrent requests: ~3,485 req/s through the gateway (direct Node → Node: ~15,900).

Caveats, stated up front: the bare Spring/Tomcat stack on this Windows machine costs ~3.7 ms p50
(`/actuator/health`), so most of the added latency is the Java HTTP stack rather than gateway logic.
The upstream call itself, measured inside the gateway, averages ~1 ms. These are local-loopback
numbers, not a cloud benchmark.

## Tests

```bash
./mvnw test
```

25 tests: crypto, gateway behaviour (routing, failover, whitelist, unknown fields, streaming, usage
chunk handling, Anthropic conversion), plus two Testcontainers suites that exercise real Redis (Lua
rate limiting) and real MySQL (Flyway schema + idempotent batch writes).

Docker Desktop notes: the tests need a reachable engine. On this machine `DOCKER_HOST` is set to
`npipe:////./pipe/docker_engine`, and `pom.xml` pins `api.version=1.44` because docker-java's default
(1.32) is rejected by Docker Engine 25+.

## Known limitations

- **Anthropic** is non-streaming, text-only, no tool calls. `temperature` is clamped to 0–1, and
  parameters Anthropic does not support (`n`, penalties, `response_format`, `logprobs`, tools) are
  dropped silently.
- **Usage estimation**: when an upstream reports no usage and cannot be asked for it, counts are
  estimated from text length and flagged `estimated=true`.
- **Mid-stream upstream failure** ends the client stream; the response status is already committed, so
  no error frame is injected. Clients see the stream stop without `[DONE]`.
- **Heartbeats** are forwarded from upstream but not generated locally.
- **Caches are per instance**: a disabled key may live up to 60 s, a route change up to 30 s, on other
  instances.
- No admin UI (Swagger covers it), no semantic cache, billing or circuit breaker — those are v2.
