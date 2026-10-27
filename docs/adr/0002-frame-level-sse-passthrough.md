# 0002 — Frame-level SSE passthrough, not byte-level

- Status: accepted
- Date: 2026-10-27

## Context

A streaming proxy wants byte fidelity. But two requirements force parsing: every OpenAI chunk carries
a `model` field that must be rewritten to the public name, and usage-only chunks must be identified so
the gateway can meter them without exposing an extra chunk to clients that never asked for one.

## Decision

Parse each complete SSE frame (buffering bytes so UTF-8 characters split across buffers are not
corrupted), then re-emit it with only the necessary changes. Comments/heartbeats, unknown fields,
ordering, and `data: [DONE]` are preserved.

## Consequences

- Not byte-identical, but semantically faithful; unknown vendor fields survive.
- A frame must be fully buffered before forwarding, which adds a sub-millisecond delay per frame.
- `SseEmitter` was rejected: its event builder does not guarantee the exact `data:` framing clients
  expect. Frames are written to `HttpServletResponse` directly.
