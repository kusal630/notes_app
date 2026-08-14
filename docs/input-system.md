# Input System

## Pipeline

```
MotionEvent
   │
   ▼
MotionEventParser (Android-only; ~200 lines)
   │  extracts per-pointer data + history samples
   ▼
RawTouchContact (pure model)
   │  pointerId, x, y, pressure, size, toolMajor, toolMinor, orientation, toolType, times
   ▼
InputNormalizer
   │  unit conversions (px → mm), clamps, fills missing toolMajor/minor from size
   ▼
PalmRejectionEngine
   │  per-contact classification → WRITING / FINGER / PALM / REJECTED
   │  WritingLock active-pointer management
   ▼
ClassifiedFrame
   │  ordered contact list + activeWritingPointerId + allowed gestures
   ▼
StrokeSmoother (per writing pointer)
   │  dead-zone noise filter → moving average / Catmull-Rom / Kalman
   ▼
StrokeBuilder
   │  point accumulation → Path → committed Stroke object
   ▼
NoteEditorState (StateFlow<Document>)
```

## MotionEventParser

Responsibilities:
- Handle `ACTION_DOWN`, `ACTION_MOVE`, `ACTION_UP`, `ACTION_POINTER_DOWN`,
  `ACTION_POINTER_UP`, `ACTION_CANCEL`.
- For every pointer: extract `toolType`, `pressure`, `size`, `toolMajor`, `toolMinor`,
  `orientation`, `x/y`.
- Replay historical/coalesced samples (`getHistoricalX/Y/Pressure/…`) so high-refresh
  hardware coalescing does not create gaps in a stroke.
- Convert to logical canvas coordinates (world space, so zoom does not corrupt input).
- Emit an `InputFrame` with an `action` enum describing the event semantics.

It holds no state about pens or palms — it is a pure adapter.

## InputNormalizer

- Converts `toolMajor`/`toolMinor` from px to millimeters using display density.
- If `toolMajor/toolMinor` are 0 (common), derives an ellipse from `size` × touch screen
  bounds and a device-specific `touchSlop`-independent constant.
- Clamps pressure/size to valid ranges; marks fields the device did not supply so the
  classifier never over-trusts them.

## Tool Classification

```kotlin
enum class ToolKind { STYLUS, FINGER, ERASER, MOUSE, UNKNOWN }
```
`MotionEvent.TOOL_TYPE_*` maps directly. If the device reports `TOOL_TYPE_STYLUS`, the
contact is accepted as writing immediately (hardware-level signal — trusted). Otherwise
it is `FINGER`/`UNKNOWN` and passes to the geometry classifier.

## Noise Filtering

- **Dead zone**: ignore movement below `touchSlop × zoom` so a stationary pen resting on
  the screen doesn't produce dots.
- **Jitter clamp**: reject single-sample excursions larger than a speed threshold that
  immediately reverse (common with capacitive noise).
- **Merge coalesced samples**: history points are ingested but not double-counted.

## Smoothing (configurable: None / Low / Medium / High)

- **None**: pass-through (raw polyline).
- **Low**: 3-point moving average.
- **Medium**: Catmull-Rom interpolation at configurable resolution between retained
  anchor points, retaining natural corners via chord-length parameterization.
- **High**: Kalman-style 1D velocity smoothing (constant-velocity model) that follows
  fast pen strokes with less lag than a naive average.

Smoothing is applied to a *decimated* anchor stream to preserve fidelity rather than
blurring every raw point. It is stroke-preserving: the first and last points are always
kept exact.

## Gesture Handling

- Two-finger gestures (pan/zoom) are only honored when *both* contacts are classified
  as `FINGER`/gesture contacts (not `WRITING`, not `PALM`).
- In `WRITING`/`STRICT` modes, secondary contacts during an active writing lock are
  rejected for gestures too, preventing a resting palm from zooming the page.
- Pan is implemented by adjusting the viewport transform; zoom is anchored to the pinch
  centroid.

## Performance Properties

- Input events are processed off the composition/UI hand-off: the canvas is a custom
  `Canvas`-based composable consuming a `PointerInputScope` event loop.
- No per-event object churn where avoidable: per-frame buffers are reused.
- Historical samples are consumed immediately so the renderer always has the newest
  geometry; drawing happens once per frame (vsync), not once per raw event.

## Files

```
input/
├── InputCapabilities.kt      device trait detection
├── MotionEventParser.kt      MotionEvent → InputFrame  (Android)
├── InputNormalizer.kt        raw → normalized contact
├── RawTouchContact.kt        pure contact model
├── PalmRejectionEngine.kt    classification + writing lock orchestration
├── PalmClassifier.kt         per-contact geometry classification
├── WritingLock.kt            active writing pointer state machine
├── PalmRejectionSettings.kt  user-configurable settings + calibration
├── RejectionMode.kt          Strict/Balanced/Relaxed/Writing
└── smoothing/
    ├── StrokeSmoother.kt     interface + mode selection
    ├── MovingAverageSmoother.kt
    ├── CatmullRomSmoother.kt
    └── KalmanSmoother.kt
```