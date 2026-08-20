# Palm Rejection Design

## Problem Statement

A passive (capacitive) stylus is electrically indistinguishable from a finger on most
Android touchscreens. Android exposes the contact through `MotionEvent` as tool type
`TOOL_TYPE_FINGER` (or `UNKNOWN`). Some devices expose active-stylus tool types and
pressure; passive stylus hardware generally does not.

Therefore **perfect hardware-level stylus identification is impossible on most
hardware**. The application implements a layered, software-based palm rejection system
that uses everything Android actually provides, degrades gracefully, and never claims
more than the hardware exposes.

## What Android Provides

The following signals are consumed where the device supplies them:

| Signal                       | Source                              | Meaning |
|------------------------------|-------------------------------------|---------|
| Tool type                    | `getToolType(pointerIndex)`         | STYLUS / FINGER / ERASER / MOUSE / UNKNOWN |
| Contact major/minor axis     | `getToolMajor/Minor(pointerIndex)`  | Physical contact ellipse (px) |
| Touch size                   | `getSize(pointerIndex)`             | Normalized contact size 0..1 |
| Pressure                     | `getPressure(pointerIndex)`         | Normalized pressure 0..1 (finger often noisy/1.0) |
| Orientation                  | `getOrientation(pointerIndex)`      | Ellipse rotation |
| Pointer count / IDs          | `getPointerCount`, `getPointerId`   | Multi-touch management |
| Action flags                 | `ACTION_POINTER_DOWN/UP`            | Contact joins/leaves mid-gesture |
| Historical samples           | `getHistorical*`                    | Coalesced points for smooth rendering |
| Device capabilities          | `InputManager`, `ViewConfiguration` | Touch size scale, multi-touch, palm classification support |

### Palm/tool classification APIs
- `MotionEvent.FLAG_*` gesture flags; on some Android 9+ devices a pointer with a huge
  contact size or `MotionEvent.ACTION_CANCEL`-style behavior hints at palm.
- `android.hardware.input.InputManager` only reports *input device* capabilities (does
  it have a stylus *device*), which does **not** mean a passive stylus is detectable.
- There is **no public API** that reliably reports "this contact is a palm". We therefore
  never claim one exists.

## The Classifier

```
Touch Event
     ↓
Identify Tool Type
     ├─ Known Stylus ───────────────→ Accept as writing input
     ├─ Known Eraser (device) ───────→ Eraser input
     └─ Finger / Unknown / Generic ──→ Geometry analysis
                                           ↓
                              contactArea = f(toolMajor, toolMinor, size)
                                           ↓
                      ┌────────────────────┴──────────────────┐
                small area                            large area
                      │                                      │
                  Possible pen                    Possible palm
                      │                                      │
                + velocity, duration,            + writing-lock state,
                + distance to active             + pointer count,
                + orientation                    + proximity to pen
                      ↓                                      ↓
                ALLOW WRITING                       REJECT / IGNORE
```

### Features extracted per contact
- `areaScore` — normalized contact area from `toolMajor × toolMinor` and `size`.
- `speed` — px/ms velocity between recent samples.
- `directionChange` — heading stability (palm contact tends to smear/rotate).
- `duration` — how long the contact has been down.
- `pointerCount` — number of simultaneous contacts.
- `distanceToWritingContact` — proximity to the locked writing pointer.
- `confidence` — weighted combination of the above.

### Adaptive thresholds
Thresholds are not magic numbers. Baseline thresholds are derived from
`InputCapabilities` (density, touch size in mm, whether the device reports stylus), then
scaled by the user's `PalmRejectionSettings`:

- `sensitivity` 0.0–1.0 multiplies the area cutoff.
- `palmSizeMillimeters` gives an explicit physical palm size anchor when the user
  calibrates it on the diagnostics screen.
- Calibration stores `medianFingerArea` / `medianPenArea` from real measured contacts.

A baseline: on a 10" tablet a fingertip contact is roughly 6–10 mm; a resting palm is
25–60 mm. The classifier uses mm-normalized area so it behaves consistently across
densities.

## Rejection Modes

| Mode       | Behavior |
|------------|----------|
| **Strict** | Rejects nearly all large contacts (> ~7 mm area). Best for handwriting. Secondary contacts are rejected unless they are stylus tool type. |
| **Balanced**| Rejects only obvious palm contacts (> ~14 mm) and keeps normal finger taps/gestures working. |
| **Relaxed** | Very permissive; only huge smears (> ~30 mm) are rejected. For browsing. |
| **Writing** | Once a small contact becomes the active writing pointer, that pointer is locked and *all* large secondary contacts are rejected regardless of size. Optimized for resting the palm. |

Mode also affects gesture handling: in Strict/Writing modes large secondary contacts are
never allowed to pan/zoom the canvas.

## Writing Lock

Rules:

1. A contact becomes the writing pointer when it is classified `WRITING` (small contact,
   or stylus tool type) on `ACTION_DOWN`/`ACTION_POINTER_DOWN`.
2. Once locked, only that pointer ID produces stroke input.
3. Secondary contacts (including the palm) are classified and rejected per mode; they
   never create strokes and never steal the writing pointer.
4. If the writing pointer lifts (`ACTION_UP` / `ACTION_POINTER_UP`), the lock clears.
5. If the locked pointer is `ACTION_CANCEL`-ed, the stroke is discarded (not committed).
6. A new lock is only established after a period of no active writing pointer, so the
   palm doesn't immediately re-claim writing.

The lock is deliberately *sticky*: it tolerates transient classification noise and
avoids flickering between pen/palm during a stroke.

## Honest Reporting

- If `InputCapabilities.supportsStylusToolType == false`, the UI states "stylus not
  exposed by this device; using software analysis".
- The diagnostics screen shows, for every live contact: tool type as reported, contact
  size, major/minor, pressure, pointer ID, classification, and confidence — exactly what
  the OS provides. No fabrication.
- Calibration lets the user *measure their own hardware* instead of assuming values.

## Evaluation

The classifier is tested with synthetic streams simulating a small pen contact, a large
palm contact, pen+palm simultaneous contacts, and finger gestures. See
`docs/testing-plan.md` for the matrix.

## Resting-Hand Layer (`RestingHandTracker`)

The size classifier cannot resolve the case that matters most when actually writing on a
tablet: a resting hand frequently appears as **several small finger-sized contacts**
(resting fingers, or the side of a hand near an edge) that are indistinguishable from a
writing fingertip by size alone. The only reliable signal is **motion** — the real writer
moves like a stroke while the resting fingers stay put.

`RestingHandTracker` runs *after* the size classifier and adds motion / timing / cluster /
edge evidence on top of the size decision. It is stateless w.r.t. the classifier: disabling
`restingHandModeEnabled` restores the exact legacy behavior.

Rules (see `RestingHandTracker.kt` for the implementation):

- **Velocity-gated stroke detection** — "stroke-like motion" is judged over a sliding
  velocity window (`velocityWindowMs`, default 120 ms, kept within the 80–180 ms design
  budget) using the *windowed* velocity (displacement between the oldest and newest sample
  in the window ÷ window span), not raw instantaneous speed. A contact counts as a stroke
  only when `windowedVelocity ≥ effectivePromoteVelocity` **and** its total path length ≥
  `movementPromoteThresholdMm`. A slow, jittery resting hand can therefore never look like
  a writer even if it drifts a few millimeters.
- **Adaptive stroke gate** — the promote velocity is raised when the resting hand itself
  produces motion: `effectivePromoteVelocity = max(minPromoteVelocityMmPerSec,
  restingNoiseMmPerSec × 3)`, where `restingNoiseMmPerSec` is an exponential moving average
  of the windowed velocity that currently-`RESTING` contacts actually produce (decay 0.8).
  A hard-resting hand cannot drown out a real stroke, and the gate decays back to the
  configured minimum when nothing is resting.
- **Hand-shift drift rejection** — a whole-hand re-anchor shows up as a cluster of ≥ 2
  contacts moving together at the same slow pace, none of which has stroke-like velocity.
  Such contacts are `RESTING` (`HAND_SHIFT_DRIFT`) instead of becoming a gesture or a
  writer, so shifting your hand mid-writing does not produce stray strokes.
- **Ambiguity-gated buffering** — a new small contact is held as `CANDIDATE` (never drawn,
  never a gesture) only when there is genuine ambiguity: an already-established `RESTING`
  finger is present, or the frame has ≥ 3 contacts with ≥ 2 non-palm, non-tool "ambiguous"
  small contacts. A pen next to a pair of large palms is *not* ambiguous (the palms are
  already confidently rejected), so it claims the lock immediately. With
  `allowImmediateDrawWhenIsolated = true` an isolated contact (no resting hand nearby)
  draws immediately without buffering; when `false` even isolated contacts are observed
  until they move like a stroke.
- **Promotion** — a buffered `CANDIDATE` is promoted to `WRITING` and claims the writing
  lock the moment it is the *only* mover (`movingIds.size == 1`) and moves like a stroke.
  Two stroke-like movers together stay `FINGER`, so two-finger pan/zoom keeps working with
  a resting palm on the screen.
- **Resting fingers** — a stationary small contact in a resting context (≥ 3 contacts,
  near a screen edge, a stationary cluster, or a palm present) becomes `RESTING` after
  `stationaryRestTimeMs` and neither draws nor drives gestures. It can be re-promoted to
  `WRITING` if it becomes the unique mover and moves like a stroke (the user starts
  writing with a finger that was already resting).
- **Sticky locked writer** — a locked writing pointer is never demoted for pausing; it is
  only cancelled when its *smoothed* contact size grows into palm territory
  (`smoothed ≥ sizeGrowthCancelThresholdMm` **and** `≥ initialSize × palmGrowthFactor`),
  so a single digitizer spike never kills an in-progress stroke.

### Diagnostic signals

Every classified contact also carries, for the debug overlay and tests:

- `windowedVelocityMmPerSec` — velocity over the sliding window.
- `pathLengthMm` — total distance travelled since down.
- `writeScore` — weighted evidence the contact is a deliberate stroke
  (0.4·velocity + 0.25·path + 0.2·continuity + 0.15·size).
- `restScore` — weighted evidence the contact is a resting hand
  (0.4·stationarity + 0.2·cluster + 0.15·edge + 0.15·size + 0.1·growth).

The frame also reports `clusterBounds` (bounding boxes of resting clusters) which the
debug overlay draws so the whole resting hand — not just one finger — is visible.

### Settings (velocity / adaptive knobs)

| Setting | Default | Meaning |
|---------|---------|---------|
| `minPromoteVelocityMmPerSec` | 120 | Minimum windowed velocity (mm/s) to count as a stroke. |
| `velocityWindowMs` | 120 | Sliding window duration for velocity & continuity (80–180 ms). |
| `sizeGrowthCancelThresholdMm` | 27.6 | Smoothed size above which a locked writer is cancelled as a palm (palmSizeThreshold × 1.15). |
| `allowImmediateDrawWhenIsolated` | true | Buffer even isolated contacts until they move like a stroke. |

### Master switch

`palmRejectionEnabled = false` bypasses the entire pipeline: every contact is treated as
plain writable/finger input (hardware pens write, erasers erase, a lone finger writes when
finger writing is enabled, finger pairs pan/zoom) and nothing is ever rejected or buffered.