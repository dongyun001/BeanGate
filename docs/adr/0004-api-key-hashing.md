# 0004 — API keys: HMAC-SHA256, not BCrypt

- Status: accepted
- Date: 2026-10-26

## Context

An API key is presented on every request, so verification sits on the hot path. It is also a
high-entropy random value (32 bytes), unlike a human password.

## Decision

Store `HMAC-SHA256(pepper, key)` in a uniquely indexed column and look it up directly. Show the
plaintext only once, at creation.

## Consequences

- Verification is a single indexed query (and a local cache hit in practice).
- BCrypt's per-record salt makes an indexed lookup impossible and adds tens of milliseconds per
  request; slow hashing is the right defence for low-entropy passwords, not for 256-bit keys.
- Rotating the pepper invalidates every existing key; treat it as a key-rotation event, not a config
  tweak.
- A 60-second local cache means a disabled key can keep working for up to a minute (single instance),
  or one TTL per instance in a cluster.
