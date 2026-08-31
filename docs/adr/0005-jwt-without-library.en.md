# 0005. Admission JWT — hand-rolled with JDK crypto, no library (stage 1)
Date: 2026-08-31 · Stage: 1 · Status: decided · [한국어](0005-jwt-without-library.md)

## Context
The queue admission token is an HS256 JWT. The approved stack (ARCHITECTURE 4-1) contains no
JWT library, and adding an off-list library requires asking a human first.

## Options
- **Implement with the JDK (Mac + Base64)**: zero dependencies, ~40 lines. HS256 is all we
  need. The signing/verification logic is fully visible, which is great for learning.
  Downside: none of a library's standard-claim validation or key rotation.
- jjwt or similar: battle-tested, industry standard. Downsides: this project only uses two
  functions (sign/verify), it adds a dependency, and the rules require human approval.

## Decision
Hand-roll it. The surface area is tiny (one issue site, one verify site), and the risk stays
small as long as constant-time comparison (MessageDigest.isEqual) and expiry checks are kept.

## Consequences
Gained: zero dependencies and a working understanding of JWT structure. Lost: a library's
defensive validation — isolated in the single file shared/token/HmacJwt to keep the
replacement point narrow. Revisit when stage 3 multiplies token usage; switch to jjwt then.
