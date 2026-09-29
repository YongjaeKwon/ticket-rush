# 0008. Payment trigger — PaymentRequested published by the confirm request (stage 3)
Date: 2026-09-11 · Stage: 3 · Status: decided · [한국어](0008-payment-trigger-payment-requested.md)
## Context
The payment module starts a payment when a note (event) arrives. The question is which note. The original architecture sketch (ARCHITECTURE 2-1) wired it straight to `ReservationHeld`, but the stage-2 payment screen starts payment only when the user picks a method and presses the button, and retry-after-decline (hold kept, ADR 0006) also presumes a user trigger.
## Options
- **Confirm request publishes `PaymentRequested`** (chosen): when the user presses pay, the confirm API writes `PaymentRequested` to the outbox and answers 202 (the switch itself is the next unit). Payment consumes only this note.
- Direct `ReservationHeld` consumption: auto-payment on hold. Shorter flow, but payment would finish before the user ever submits the card form — incompatible with the stage-2 screen and retry semantics, and the confirm API would have nothing left to do.
## Decision
`PaymentRequested`. The user's "pay" button stays the source of truth, carrying the stage-2 decision into stage 3. The event carries only what payment needs — reservationId, scheduleId (partition key), userId, amount. Results are published as `PaymentApproved` / `PaymentDeclined` (card declined — hold kept, retryable) / `PaymentFailed` (timeout or system error — compensation target). The decline/failure split follows ARCHITECTURE 2-2: a decline is not a state transition; only a failure leads to releasing the hold.
## Consequences
Gained: an event flow consistent with the screens and retry rules; the ARCHITECTURE 2-1 diagram will be updated to match. Lost: one extra event hop between hold and payment (latency a direct wiring would not have). Until the confirm API's 202 switch lands, the payment module receives no production traffic — in this unit, tests inject the note directly.
