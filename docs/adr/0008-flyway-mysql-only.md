# 0008 — Flyway for MySQL, Hibernate DDL for local H2

- Status: accepted
- Date: 2026-10-27

## Context

The project has two schema consumers: real MySQL in Docker, and in-memory H2 for zero-setup local runs
and for HTTP tests. Running one set of migrations against both dialects invites type-mapping
surprises, while letting Hibernate own the production schema makes changes invisible in review.

## Decision

- MySQL profile: Flyway owns the schema (`db/migration/V*.sql`), `ddl-auto: none`.
- Local/test profiles: Hibernate `ddl-auto: update`/`create-drop` from the JPA entities, Flyway off.

## Consequences

- Production DDL is explicit, versioned, and reviewable; the migration is exercised by a Testcontainers
  MySQL test.
- Two schema descriptions must stay in sync. The `usage_log` table is the one hand-written JDBC table;
  its entity is kept only so H2 and MySQL agree on column names.
- Adding a column means touching both the entity and a new migration.
