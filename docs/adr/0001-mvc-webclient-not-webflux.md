# 0001 — Spring MVC + WebClient, not WebFlux

- Status: accepted
- Date: 2026-10-26

## Context

The gateway is I/O-bound: almost every request waits on an upstream HTTP call, and streaming requests
hold a connection for seconds. WebFlux would model this naturally, but it moves the whole codebase
(security filters, error handling, JPA access) onto reactive types and raises the debugging cost.

## Decision

Use Spring MVC with `WebClient` (Reactor Netty) for upstream calls, write SSE frames directly to the
servlet `OutputStream`, and enable virtual threads for the blocking streaming path.

## Consequences

- Familiar servlet stack; JPA repositories stay blocking and simple.
- Streaming holds one virtual thread per open conversation, which is cheap enough for this project.
- The request pipeline is synchronous, so a slow upstream blocks only its own virtual thread.
- Mixing MVC and Reactor is a real interview talking point, but it means blocking a Reactor stream
  (`toStream()`) — acceptable for a servlet-based proxy, and cancellation still propagates.
