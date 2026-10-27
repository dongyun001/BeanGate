# 0007 — No outer transaction around cached reads

- Status: accepted
- Date: 2026-10-27

## Context

The first benchmark measured ~15 ms of added latency per request against MySQL. Profiling by endpoint
showed that even a request that never touches the database (a cached model lookup that ends in 404)
cost ~10 ms, while `/actuator/health` cost ~4 ms. The missing ~6 ms was
`@Transactional(readOnly = true)` on `ApiKeyService.authenticate` and `Router.resolve`: the interceptor
borrowed a JDBC connection, began a transaction, and committed it on every request — even on a cache
hit.

## Decision

Cached read paths carry no outer transaction. Spring Data repository calls are transactional on their
own, so a cache miss still gets a proper read-only transaction, and a cache hit touches no connection
at all.

## Consequences

- Measured on the same machine against MySQL: chat p50 fell from ~10.6 ms to ~2.5 ms, and throughput
  at 10 concurrent requests rose from ~494 to ~3,485 req/s.
- Rule of thumb for this codebase: `@Transactional` belongs on writes and multi-query reads, never on
  a method whose happy path is a cache hit.
- When a cache miss spans several queries, wrap those queries in a dedicated transactional method.
