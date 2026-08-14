# Drawing Engine

## Goals

- Sub-frame input latency: a stroke segment is drawn the same frame it is produced.
- Thousands of strokes remain interactive; 10k-stroke pages remain loadable and scrollable.
- Incremental rendering: never redraw the whole page per touch event.

## Architecture

```
InkRenderer
 ├── committedLayer : immutable List<Stroke> → rendered into a cached Bitmap
 ├── activeLayer    : in-progress stroke drawn directly on the frame
 └── invalidate(dirtyRect) : only re-render affected tiles

PageBackgroundRenderer
 └── paper template (ruled/grid/dots/Cornell/music…) drawn once per page size/zoom level
```

### Rendering path
1. Committed strokes are baked into a background bitmap layer that is re-rendered only
   when a stroke is committed, a stroke is edited, or zoom changes meaningfully.
2. The active stroke is drawn as a fresh `Path` every frame on top of the cached layer —
   this is the only per-event work, and it is cheap (a handful of segments).
3. Dirty-rectangle invalidation limits `drawImage`/`Canvas` work to the bounding box of
   the changed region.

### Zoom strategy
- Rendering is done in *world space*; the canvas applies a `scale`/`translate` transform.
- Bitmap layers are rasterized at a base resolution and re-rasterized when the zoom
  crosses a 2× power-of-two threshold, so cached strokes stay sharp without re-rendering
  every frame. Vector paths for committed strokes are retained so re-rasterization is
  exact, not upscaled-blurry.

## Stroke Representation

A `Stroke` stores:

```kotlin
data class Stroke(
    val id: Long,
    val penType: PenType,        // BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER, MONOLINE, CALLIGRAPHY
    val color: Long,             // ARGB
    val width: Float,            // mm (world units)
    val opacity: Float,          // 0..1
    val points: List<Point>,     // world coordinates, anchor-decimated
    val smoothing: SmoothingMode,
    val metadata: StrokeMetadata = StrokeMetadata()
)
```

Points are `Float` pairs packed into a `FloatArray` internally for serialization
efficiency (`[x0,y0,x1,y1,…]`), avoiding per-point object overhead.

## Pen Rendering

- **Ballpoint**: single-width polyline with round caps/joins; slight width taper at
  start/end based on velocity for a natural feel.
- **Fountain pen**: width varies with speed and (if reported) pressure; semi-glossy cap.
- **Pencil**: textured stroke — renders the base line plus a low-alpha duplicated path
  offset by a tiny deterministic jitter, giving graphite grain without random noise
  (kept stable across frames and reproducible on export).
- **Marker**: wide, uniform, 60–80% opacity, hard edges.
- **Highlighter**: wide translucent stroke with `PorterDuff.Mode.SRC_OVER` (or SRC_ATOP
  over paper background for the "ink under paper" look); drawn in its own pass so
  highlighters correctly layer below/above strokes per design.
- **Monoline**: constant width.
- **Calligraphy**: width modulated by stroke direction relative to a configurable nib
  angle (perpendicular to motion → thin, aligned → thick), rendered as a filled polygon
  between left/right edges.

All pens render through a shared `InkPathBuilder` that turns the smoothed point stream
into a `Path` and optionally a width-profile into a filled outline.

## Hardware Acceleration

- The canvas uses hardware-accelerated `Canvas` (default for Compose/View).
- Bitmap caching uses `Bitmap.Config.ARGB_8888` with `isHardwareConfig` avoided for the
  mutable cache layer (hardware bitmaps cannot be modified); rendering is done via a
  software-drawable intermediate that is uploaded as a texture.
- Tiles: a page is split into fixed tiles; only tiles intersecting the dirty region are
  re-rendered, bounded by viewport for memory efficiency (large pages are not fully
  rasterized at once at high zoom).

## Memory

- The committed layer cache keeps at most 3 zoom-resolution copies; LRU eviction.
- Page thumbnails are downsampled and cached by size.
- `largeHeap="true"` is set for tablets but the engine is designed to not depend on it.

## Performance Validation

Tests in `docs/testing-plan.md` cover 100/1k/10k-stroke pages with a budget of:
- commit ≤ 8 ms,
- incremental draw ≤ 4 ms on mid-range tablets,
- no full-page redraws during continuous writing.