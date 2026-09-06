# Palm Rejection Research Notes (external sources)

_Collected during the Vellum rework (2026-09-06). Sources: Android official
documentation (developer.android.com) — extracted verbatim points, condensed._

## 1. Signals Android provides that we should consume

### FLAG_CANCELED (API 33+)
- Android itself marks unintentional touches: "typically set when the user was
  accidentally touching the screen, such as by gripping the device, or placing
  the palm of the hand on the screen."
- Access: `(event.flags and MotionEvent.FLAG_CANCELED) != 0`.
- Required behavior: undo the last motion set **from the last ACTION_DOWN of that
  pointer** (found via `getPointerId(actionIndex)`) and re-render.
- Status in Vellum: NOT consumed -> improvement item.

### ACTION_CANCEL semantics (per-pointer)
- Fired for navigation gestures and OS palm rejection.
- Correct handling: identify the active pointer with
  `getPointerId(getActionIndex())`, remove ONLY the stroke created by that
  pointer from the input history, re-render the scene.
- Implication: we must keep a per-pointer input history to enable rollback, and
  clear per-pointer classifier/tracker state (fixed 2026-09-06 in
  PalmRejectionEngine/RestingHandTracker; engine-level rollback of provisional
  ink is a further improvement item).

### Hover (AXIS_DISTANCE) and hover actions
- For stylus: `getAxisValue(AXIS_DISTANCE)` 0.0 = contact, larger = hovering.
- Hover events (ACTION_HOVER_ENTER/MOVE/EXIT) arrive without contact.
- Rejection use: when a stylus is HOVERING (distance > 0), any simultaneous
  finger contact is almost certainly a palm -> suppress finger inking while
  stylus hover is active.
- Status in Vellum: NOT consumed -> improvement item.

### Edge flags (getEdgeFlags, ACTION_DOWN only)
- Reports display-edge contact; useful for edge-suppression heuristics (bezel
  grips produce edge-flagged large slow contacts).
- Status in Vellum: NOT consumed -> improvement item (low priority).

### Stylus identification
- `TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` (eraser also = inverted stylus posture).
- Verify pressure is normalized to 0..1 (values >1 possible); normalize before use.

## 2. Official recommended pipeline (Google)
1. Start stroke on ACTION_DOWN (per pointer), extend on ACTION_MOVE, finish on
   ACTION_UP.
2. Keep a history of user inputs so unwanted touches can be removed and the
   scene re-rendered (provisional ink + rollback model).
3. On ACTION_CANCEL / FLAG_CANCELED: rollback that pointer's motion set and
   re-render.
4. For full-screen apps: use WindowInsetsController
   `setSystemBeansBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)` (sic, method
   name is setSystemBarsBehavior) so navigation swipes don't create stray marks.

## 3. Latency (adjacent but relevant to writing feel)
- Low-latency rendering: front-buffer rendering while a pointer is down
  (GL front-buffer / Jetpack low-latency graphics).
- Perceived latency: Jetpack motion prediction library predicts future motion
  events. (Would be a new dependency; evaluate later against the
  no-new-dependencies constraint.)

## 4. Concrete improvement backlog derived from this research
| # | Item | Priority |
|---|------|----------|
| 1 | Consume FLAG_CANCELED (API 33+): mark pointer as palm, rollback its provisional ink | High |
| 2 | Per-pointer ACTION_CANCEL rollback (only the canceled pointer's stroke, not global reset) | High |
| 3 | Stylus-hover gating: while stylus hover active, suppress finger/palm inking | High |
| 4 | Keep per-pointer provisional ink history to enable clean rollback + re-render | High |
| 5 | Edge-flag-assisted edge suppression for bezel grips | Medium |
| 6 | Normalize pressure >1.0 cases before feature use | Medium |
| 7 | Evaluate Jetpack low-latency graphics / motion prediction | Later (new dep) |

## 5. Validation methodology (from panel review + research)
- Log and heat-map accepted vs rejected contacts (x/y, toolMajor/minor, size,
  zone hits, classification) behind a debug flag.
- Calibration doodles to measure false-rejection rate for small/slow strokes.
- Rolling per-device percentile normalization of geometry features to survive
  screen-protector / glove / moisture drift.
