# 0004. Seat-status bitmap — catalog reads confirmed_seat directly (stage 1)
Date: 2026-08-28 · Stage: 1 · Status: decided · [한국어](0004-catalog-reads-confirmed-seat.md)

## Context
The seat-status API belongs to catalog, but the fact of confirmation (confirmed_seat) belongs
to reservation — and the reservation module does not exist yet.

## Options
- **Catalog reads it with a read-only native query**: one JdbcClient SELECT, no JPA entity.
  Simple, no cross-module code dependency. Downside: an implicit coupling at the DB-schema level.
- Create a query port in reservation and have catalog call it: cleaner boundary, but it forces
  building a module that doesn't exist yet just for a read — an inversion of the build order.

## Decision
Read it directly. This is a data dependency, not a code dependency, and it points in the safe
direction (reading confirmed facts). No JPA mapping is created — the mapping owner of this
table is the future reservation module.

## Consequences
Gained: catalog stays thin and the build order stays intact. Lost: schema coupling — hidden
behind the ConfirmedSeatReader port so there is a single replacement point. Revisit at the
stage-3 service split, replacing it with reservation's status-event projection (or API).
