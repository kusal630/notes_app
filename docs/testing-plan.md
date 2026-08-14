# Testing Plan

## Principles

- Never disable a test to make a build pass.
- Never hide a failure with an empty catch.
- The highest-value tests are for the pure input/palm-rejection core because that is the
  hardest logic and the one most dependent on real hardware behavior.

## Unit Tests (JVM, fast, run in CI)

### Input pipeline (`input/`)
Simulated contact streams are built from pure data, no device needed:

- `MotionEventParserTest` — parsing/pointer/action/history coverage.
- `PalmClassifierTest` — matrix over contact sizes:
  - small pen-like contact (4–8 mm) → `WRITING`
  - fingertip (8–14 mm) → `FINGER` (allowed, not stroke) per mode
  - palm (25–60 mm) → `PALM`/`REJECTED`
  - stylus tool type → always `WRITING` regardless of size
  - unknown tool type → geometry fallback
- `WritingLockTest` — lock establishment, palm-down-while-writing (secondary contact
  rejected), pointer switch rejection, cancel behavior, lock release on lift.
- `RejectionModeTest` — Strict/Balanced/Relaxed/Writing produce expected classifications
  on identical synthetic frames.
- `InputNormalizerTest` — mm conversion, missing toolMajor fallback, clamping.
- `SmootherTest` — None/Low/Medium/High: endpoints preserved, jitter reduction
  (variance lower than input), fast-stroke lag bounds for Kalman.

### Drawing (`render/`, `editor/`)
- `StrokeBuilderTest` — decimation, dead-zone skip, first/last point fidelity.
- `UndoRedoTest` — add/move/delete/insert commands invert correctly; stack bounds.
- `SelectionTest` — lasso/rect hit-testing on strokes, move/resize/rotate math.
- `ShapeToolTest` — raw stroke → shape fit (line/rect/circle/star), hold-to-straighten.

### Persistence (`data/`)
- Round-trip: save `PageContent` → load → equal.
- Backward/forward serialization version tolerance.
- Corruption: truncated blob → clear error, prior good state kept.
- Recovery journal: interrupted write → previous data intact.
- Migration test (Room) — schema v1 → current upgrades without data loss.

## Instrumented Tests (`app/src/androidTest`)

On a real tablet (critical: hardware behavior is unknowable from code alone):

- Editor writes a stroke, restarts activity, stroke persists.
- Dark/light theme rendering smoke tests.
- Portrait↔landscape rotation preserves editor state.
- Performance: page with 100 / 1,000 / 10,000 synthetic strokes:
  - commit time, incremental frame draw, scroll/zoom FPS sampled via `FrameMetrics`.

## Manual Hardware Test Matrix (must be run on target device)

| Test | Procedure | Pass criteria |
|------|-----------|---------------|
| Small contact writing | Write with passive stylus | Smooth stroke, no palm strokes |
| Palm rest | Write with palm fully on screen | No random strokes/gestures |
| Pen+palm | Rest palm, then write; write then rest palm | Active pointer never switches |
| Two-finger pan | Two fingers drag | Canvas pans; no stroke |
| Pinch zoom | Two fingers pinch | Smooth zoom anchored at centroid |
| Multi-touch writing | Stylus + finger simultaneously | Only stylus writes |
| Calibration | Diagnostics screen, measure finger & palm | Saved values adapt thresholds |
| Undo after palm | Palm triggers no action | Undo stack unaffected |
| Crash mid-write | Force-stop while writing | Last committed stroke recovered |
| 10k strokes | Scripted generation | Interactive pan/zoom, no jank > budget |

## Performance Budgets

- Stroke commit ≤ 8 ms (JVM instrumentation, mid-range tablet).
- Incremental active-stroke draw ≤ 4 ms.
- No full-page redraw during a continuous writing stream.
- Page load with 10k strokes ≤ 500 ms.

## Fixtures

- `input/TestTouchFactory.kt` — builders for synthetic pen/finger/palm/multi-touch frames
  (pure Kotlin, reused by several suites).
- `data/TestDocumentFactory.kt` — 1k/10k-stroke `PageContent` generators.
- `render/TestInkRenderer.kt` — renders into an offscreen bitmap for pixel-level
  assertions (color presence, stroke bounds).