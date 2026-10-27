# 0003 — Forward JSON trees, never bind to typed DTOs

- Status: accepted
- Date: 2026-10-26

## Context

The OpenAI request schema only grows (`tools`, `response_format`, `logprobs`, `reasoning_content`,
`stream_options`, …). A DTO with a fixed field set silently drops anything it does not know about,
which is worse than rejecting the request: the client gets a subtly different answer.

## Decision

Read bodies as `JsonNode`, mutate only `model` (public → upstream on the way in, upstream → public on
the way out), and forward everything else untouched. Provider adapters convert explicitly when the
upstream protocol differs (Anthropic), and that conversion is where unsupported fields are dropped.

## Consequences

- New OpenAI parameters work without a code change.
- No compile-time schema safety: adapter behaviour is covered by tests instead.
- Responses are also trees, so unknown upstream fields reach the client unchanged.
