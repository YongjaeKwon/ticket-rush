# Design Foundation — GATE v3.1 · Korean-style × iOS widgets, flat (2026-08-28)

[한국어](design-foundation.md) | **English**

> The reference for stage-2 (web) and stage-5 (app) screens. Always updated together with the
> mockup/prototype artifact ("Ticket Rush GATE").
> Rule: no color, font, shadow, or radius that is not in this document may appear in screen code.
> History: v1 wayfinding-signage (dropped as "too Western award-site") → v2 Korean-style →
> **v3 Korean information structure with an iOS-widget premium surface** → v3.1 flat.

## 1. Concept — "Korean ticketing grammar × iOS widget surface"

The information structure (what goes where) follows **Interpark-style ticketing**; the surface
(how it looks) borrows the **iOS widget/wallet vocabulary**. Metaphors are components:

- Open countdown = **lock-screen dark widget** (flat dark card + large numerals; digits turn red when imminent)
- Waiting queue = **activity ring** (solid brand-color stroke with the queue position inside)
- Seat-hold timer = **Dynamic-Island-style capsule** (floating dark pill, pulsing red dot + time left)
- Completed ticket = **wallet pass** (dark header + field grid + perforation + barcode)
- Top bar / bottom CTA bar / tab bar = **frosted glass** (blur 18px + saturate 1.6)

The Korean skeleton stays: back-arrow header, bottom tab bar, schedule-selection cell,
terms accordion, separated booking fee, payment-method tiles, full-consent row, gray notice
boxes, remaining-seat counts.

### Banned list (AI smell + off-tone)

- Inter / Roboto / Space Grotesk, emoji icons, cream+serif+terracotta, near-black + one neon accent
- Decorative uppercase Latin labels (SINGLE NIGHT, ADMIT ONE, …), brutalist hairline grids
- **Gradient/glow backgrounds are fully banned** (v3.1) — depth comes from solid surfaces +
  shadows only; blur is allowed only on the three frosted bars
- Flat 1px-bordered cards — shadows define surfaces here

## 2. Tokens

### 2-1. Color

| Token | Value | Use |
|---|---|---|
| `--bg` | `#F2F3F7` | Ground (iOS grouped background) |
| `--surface` | `#FFFFFF` | Cards |
| `--glass` | `rgba(255,255,255,.72)` + `backdrop-blur(18px) saturate(1.6)` | Top bar · CTA bar · tab bar |
| `--ink` | `#16181D` / `--sub` `#8A8F98` | Body / secondary |
| `--line` | `rgba(60,60,67,.12)` | Hairlines (row separators inside cards only) |
| `--brand` | `#2E5BFF` (CTA, my seat, ring — all solid) | Action |
| `--danger` | `#FF453A` (iOS red) | Hold timer · D-day · urgency |
| art | `#1D2B6B` flat + seat-dot pattern | Countdown · poster · wallet header · main banner |

Seats: available `#D6E1FF`, mine = solid brand + ring shadow, taken/sold `rgba(60,60,67,.12)`,
blocked transparent. The art surface carries a dot grid (the seat map itself is the brand
graphic) and a "seat signature" row where a few dots are lit.

### 2-2. Typography

- **Pretendard** everywhere (designed to match SF — the basis of the iOS tone), falling back
  to Noto Sans KR webfont. No separate display face — **hierarchy comes from weight only**
  (800/700/600/500/400).
- Large numerals (countdown 52px, queue position 40px) get -0.02em tracking + `tabular-nums`.
- IBM Plex Mono only for booking numbers and barcode captions.

### 2-3. Surface, line, shadow, radius — "shadows make surfaces"

- Radius: cards **22px** (squircle tone), banner/pass 24px, buttons 18px, cells 14px,
  seats 6px, capsules/badges pill.
- Card shadow (fixed 2 layers): `0 1px 2px rgba(16,19,25,.04), 0 10px 28px rgba(16,19,25,.09)`.
- CTA shadow: brand-colored `0 6px 16px rgba(46,91,255,.24)`. No gradients, no inset highlights.
- No outer borders on cards. Separators only between rows inside a card.
- Phone frame and mockups scale to the viewport (`min(820px, 100vh - 180px)`) — the page
  fits on one screen without scrolling.

### 2-4. Motion

- Two easings: standard `cubic-bezier(.16,1,.3,1)`, spring `cubic-bezier(.34,1.4,.44,1)`
  (buttons, island, check pop). Durations 120/240/450ms.
- Button feedback `scale(.97)`, seat tap `scale(.85)`, island enters with a spring slide from the top.
- One big moment per screen: countdown widget / ring progress / seats dimming / check pop.
  `prefers-reduced-motion` respected.

## 3. Screens (implemented in the mockup/prototype)

1. **List** — iOS large title "지금 예매", dark-art main banner, upcoming list with
   notification pills, frosted tab bar, status bar (9:41)
2. **Detail** — poster (mini art panel) + info, schedule cell with check circle,
   **lock-screen countdown widget**, terms accordion
3. **Queue** — **activity ring** card (position, gauge, stat pills), notice box, admission flash
4. **Seat selection** — iOS segments (A/B/C/D), STAGE pill, seat-grid card (virtual competitors
   dimming seats), frosted selection bar, **island timer** appears on hold
5. **Payment** — order card (fee separated), payment-method monogram tiles (card/KakaoPay/
   NaverPay/TossPay/PAYCO/mobile — each with its brand color and a per-method note),
   full consent + iOS switch (failure simulation), emphasized total
6. **Done** — check pop + **wallet pass** (dark header, field grid, perforation, barcode)

Errors show a user message plus the machine code: `SEAT_ALREADY_HELD`, `HOLD_EXPIRED`, `PAYMENT_DECLINED`.

## 4. Stage-2 implementation guide

- Map tokens 1:1 into the Tailwind theme (`bg/surface/glass/ink/sub/line/brand/danger`);
  arbitrary values are banned.
- Pretendard subset via `next/font`. `backdrop-filter` only on the three bars
  (low-end phone cost — verified with Lighthouse in stage 4).
- The seat map ships as Canvas — the prototype's DOM grid is demo-only; carry over the cell
  spec (square, radius 6px, gap 5px) and state colors.
- Rings, islands, and patterns in CSS/SVG only. No motion libraries (outside the
  ARCHITECTURE 4-1 list).
