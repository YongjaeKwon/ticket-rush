# 0003. No foreign keys on contention tables (reservation, confirmed_seat)
Date: 2026-08-28 · Stage: 1 · Status: decided · [한국어](0003-no-fk-on-contention-tables.md)

## Context
While designing V1__init.sql. The four catalog tables are linked with FKs; the question is
whether the hot tables (reservation, confirmed_seat) should also carry FKs to schedule/seat.

## Options
- **No FKs, indexes only**: removes parent-row shared-lock and validation cost on the write
  hot path. Catalog is a read-only seed, so there is effectively no path that breaks
  referential integrity.
- With FKs: the DB guarantees integrity, but massive concurrent INSERTs contend on parent
  key locks and add noise to the stage-4 load numbers.

## Decision
No FKs. A nonexistent seat_id is filtered at the application layer (seat lookup), and the
core invariant — no double confirmation — is guarded not by FKs but by the
confirmed_seat primary key (schedule_id, seat_id).

## Consequences
Gained: a simple, fast hold-INSERT path. Lost: DB-level referential integrity — we rely on
the premise that catalog is immutable after seeding. Revisit if catalog ever becomes writable.
