# 0006 — TPM is approximate (counted after completion)

- Status: accepted
- Date: 2026-10-27

## Context

RPM can be enforced exactly at admission: one request is one request. TPM cannot, because the number
of completion tokens is unknown until the upstream finishes. Reserving
`prompt_tokens + max_tokens` and reconciling later is exact-ish but leaks reservations on every error
path and makes the Lua script considerably harder.

## Decision

Enforce RPM with an exact sorted-set sliding window. Enforce TPM against tokens **already consumed**
in the last minute, counted with two weighted minute buckets; the async usage writer feeds the counts
back after each completed call.

## Consequences

- A burst inside one window can overshoot TPM by the in-flight requests' completion sizes. The error is
  bounded and documented; RPM still bounds request rate.
- No reservation bookkeeping, so no leaked quota after client aborts or upstream errors (a cancelled
  stream contributes its partial tokens when it ends).
- The reserve-then-reconcile variant remains a documented v2 option.
