# Architecture

## Overview

Premium Notes is a production-quality handwriting note-taking application for Android
tablets, optimized for passive/capacitive styluses and touch screens. The application is
designed around a strict separation between raw platform input and the pure, testable
core of input analysis, drawing, and document state.

The guiding rule of the whole project is:

> **Never claim that perfect hardware-level stylus identification exists when Android
> does not expose it.** All palm rejection is software-based, configurable, and
> explicitly adaptive to the hardware actually present.

## Tech Stack

| Concern             | Choice                                             | Rationale |
|---------------------|----------------------------------------------------|-----------|
| Language            | Kotlin                                             | Android-first, coroutines, null safety |
| UI                  | Jetpack Compose + Material 3                       | Tablet-adaptive layouts, recomposition control |
| Drawing             | Android `Canvas`/`Path` on a dedicated canvas composable | Predictable, hardware accelerated, low latency |
| Persistence         | Room (SQLite)                                      | Structured entities, transactional writes, migrations |
| Serialization       | kotlinx.serialization JSON                         | Compact stroke blob serialization |
| Async               | Coroutines + Flow                                  | Non-blocking persistence and loading |
| DI                  | Manual application container (`AppContainer`)      | Zero magic, replaceable systems, testable |
| PDF export          | Android `PdfDocument`                              | Vector-quality handwriting via `Path`, not screenshots |
| PDF import          | Android `PdfRenderer`                              | Native, no extra dependency |

The dependency list is deliberately small. Every dependency has a reason; native
platform APIs are preferred wherever they are sufficient.

## Layer Model

```
┌────────────────────────────────────────────────────────────┐
│ UI (Compose)                                               │
│  Home / Editor / Diagnostics / Settings                    │
│  NoteEditorState (StateFlow), EditorViewModel              │
├────────────────────────────────────────────────────────────┤
│ INPUT PIPELINE (pure + platform)                            │
│  MotionEventParser   (MotionEvent -> RawTouchContact)       │
│  PalmRejectionEngine (RawTouchContact -> ClassifiedContact) │
│  WritingLock         (active writing pointer)               │
│  StrokeSmoother      (filter + smooth point stream)         │
├────────────────────────────────────────────────────────────┤
│ DOMAIN CORE (pure Kotlin, unit tested)                      │
│  StrokeBuilder, tools (Pen/Highlighter/Eraser/Select)       │
│  Document model: Page + objects (Stroke, Text, Image, Shape)│
│  UndoRedo (command stack)                                   │
├────────────────────────────────────────────────────────────┤
│ RENDERING                                                   │
│  InkRenderer (Path -> Canvas)                               │
│  TiledRenderer, dirty-region invalidation                   │
├────────────────────────────────────────────────────────────┤
│ DATA                                                        │
│  Room DAOs -> NotesRepository -> ViewModels                 │
│  Document serialization / page blobs                        │
│  Autosave + crash recovery                                  │
├────────────────────────────────────────────────────────────┤
│ EXPORT                                                      │
│  PdfExporter (vector paths) / ImageExporter                 │
└────────────────────────────────────────────────────────────┘
```

## Key Architectural Decisions

### 1. Pure core separated from Android
The palm classifier, input normalization, smoothing, document model, and undo/redo are
written as pure Kotlin with no Android dependencies. Only the thin `MotionEventParser`
and renderer touch Android APIs. This makes the entire input pipeline unit-testable on
the JVM and allows simulation of synthetic "palms" and "pens" in tests.

### 2. Input pipeline as a stream, not callbacks
Every `MotionEvent` is parsed into a frame of `RawTouchContact`s, classified into
`ClassifiedContact`s, and consumed by the active tool. The canvas never creates a stroke
point from a raw `ACTION_MOVE` directly; it consumes the classified, normalized,
smoothed stream. This is the pipeline from the requirements:

```
Raw Touch Event → Input Normalization → Tool Classification → Palm Rejection
→ Noise Filtering → Point Smoothing → Stroke Construction → Rendering → Persistence
```

### 3. Incremental rendering
The canvas renders committed strokes from an immutable list and the in-progress stroke
from the live buffer. Invalidation is limited to the union of dirty rectangles around
new stroke segments. No full-page redraw per touch event. A background bitmap cache
stores the committed layers and is re-rendered only when a stroke is committed.

### 4. Command-based undo/redo
Undo/redo is implemented as a stack of `EditorCommand` objects (AddStroke, DeleteStroke,
MoveObjects, InsertImage, …) with invert operations. It is not a screenshot history.

### 5. Autosave and recovery
Writes are queued through a repository that debounces persistence and applies mutations
to an in-memory document state synchronously (for low latency) while persisting
incrementally on a background dispatcher. A journal/atomic-rename strategy protects
against partial writes corrupting a notebook.

### 6. Manual DI
`AppContainer` builds the small set of singletons (input capabilities, palm rejection
engine + settings, repository). Systems depend on interfaces where they could be
replaced (e.g. `HandwritingRecognitionService`).

## Package Layout

```
com.premiumnotes
├── data/          Room, entities, DAOs, repository, serialization
├── input/         MotionEventParser, PalmRejectionEngine, classifier, WritingLock,
│                  settings, smoothing
├── model/         document model (Page, Stroke, TextObject, ImageObject, ShapeObject)
├── render/        InkRenderer, page background renderer, tiled cache
├── editor/        NoteEditorState, tool implementations, undo/redo
├── export/        PdfExporter, ImageExporter
├── ui/            theme, navigation, home, editor, diagnostics, settings, components
└── di             AppContainer
```

## Lifecycle

- The editor keeps all unsaved changes in `NoteEditorState` (in-memory document).
- Autosave persists on a debounce timer and on pause/stop.
- On process death, Room retains the last persisted state; a recovery journal restores
  any write that was interrupted before rename.
- Rotation is handled without recreating the editor state via `configChanges` on the
  activity plus state hoisting in the ViewModel.

## Compatibility

- `minSdk 26`, `targetSdk 35`, `compileSdk 35`.
- Uses modern storage (app-internal files + scoped external access via photo picker).
- No legacy `getExternalStoragePublicDirectory` / `READ_EXTERNAL_STORAGE` usage.

## Open Items / Honest Limitations

- Handwriting recognition is only scaffolded as `HandwritingRecognitionService` (empty
  interface + no-op). Real OCR is a future integration; the app will never claim
  handwriting-to-text works until it does.
- Cloud sync is out of scope by design (offline-first, privacy-first).
- `InputCapabilities` detects hardware traits; when the device does not expose stylus
  tool types, the app explicitly reports "unknown/not exposed" and relies on the
  software classifier.