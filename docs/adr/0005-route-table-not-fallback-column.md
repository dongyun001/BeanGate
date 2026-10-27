# 0005 — Routes are rows, not a `fallback_provider_id` column

- Status: accepted
- Date: 2026-10-26

## Context

The obvious model is `model_mapping(public_model, provider_id, upstream_model, fallback_provider_id)`.
It breaks as soon as the fallback lives on a different provider, because that provider usually expects
a different upstream model name (`deepseek-chat` vs `qwen-max`).

## Decision

One row per (public model, provider) with `priority` and `upstream_model`:
`model_route(public_model, provider_id, upstream_model, priority, enabled)`.

## Consequences

- Failover is "take the next row by priority"; multi-level failover is a data change.
- Each channel keeps its own upstream model name, so a fallback is not silently sent the wrong model.
- Capability-aware routing (e.g. skip a provider that cannot stream) can be added per row later.
