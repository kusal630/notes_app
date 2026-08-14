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