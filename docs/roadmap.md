# Roadmap

## Phase 0 — Foundation ✅
- [x] Environment: Android SDK, Gradle wrapper, project skeleton
- [x] Version catalog, AGP/Kotlin/Compose configuration
- [x] Buildable minimal app + theme + icon

## Phase 1 — Input System ✅ (M1 complete)
- [x] Documentation (architecture, palm rejection, input, drawing, data model)
- [x] `InputCapabilities` device detection (honest, hardware-derived)
- [x] `MotionEventParser` + `InputNormalizer`
- [x] Pure `PalmClassifier` + `PalmRejectionEngine` + `WritingLock`
- [x] Rejection modes (Strict/Balanced/Relaxed/Writing)
- [x] Diagnostics + calibration screen (live per-contact readout, mode selector, save-pen/finger/palm)
- [x] Smoothing: None/Low/Medium/High (streaming, endpoint-exact)
- [x] Unit tests for the full input pipeline (33 passing)

## Phase 2 — Drawing Engine
- [ ] `InkPathBuilder`, pen rendering (ballpoint first)
- [ ] Low-latency active-stroke rendering + committed-layer bitmap cache
- [ ] Smoothing: None/Low/Medium/High
- [ ] Dirty-rect invalidation, zoom/pan viewport

## Phase 3 — Editor
- [ ] Notebook list + creation
- [ ] Page list, thumbnails, page management
- [ ] Editor scaffold: toolbar, top bar, paper background
- [ ] Editor state: tools, undo/redo command stack

## Phase 4 — Persistence
- [ ] Room schema, DAOs, repository
- [ ] Document serialization (strokes, text, image, shape)
- [ ] Autosave (debounce + lifecycle hooks)
- [ ] Crash recovery journal

## Phase 5 — Tools
- [ ] Full pen set (fountain, pencil, marker, highlighter, monoline, calligraphy)
- [ ] Color system (palette, custom, recent, favorites)
- [ ] Erasers (stroke / segment / area)
- [ ] Selection (lasso, rect, move/resize/rotate/duplicate/delete)
- [ ] Shapes + hold-to-straighten
- [ ] Text tool
- [ ] Image insertion (photo picker, camera), crop/rotate

## Phase 6 — Export
- [ ] PDF export (vector paths, backgrounds, images)
- [ ] PNG/JPEG export (page + notebook)
- [ ] PDF import + annotation

## Phase 7 — Polish
- [ ] Settings screens (writing, gestures, appearance, storage, advanced)
- [ ] Toolbar customization
- [ ] Dark mode polish, animations, accessibility
- [ ] Performance passes (100/1k/10k strokes)

## Phase 8 — Ship readiness
- [ ] Instrumented hardware test matrix
- [ ] Release build, proguard, signing docs
- [ ] Play/F-Droid packaging notes

## Honest Limitations (never faked)
- **Handwriting recognition**: abstraction only (`HandwritingRecognitionService`). No
  OCR until a real engine is integrated. Search covers notebook/page titles and typed
  text only until then.
- **Passive stylus detection**: strictly software-based; the diagnostics screen reports
  exactly what the device exposes. No claims of hardware stylus identification when the
  OS provides none.
- **Cloud sync**: out of scope (offline-first).

## Milestones
1. **M1**: Input pipeline + diagnostics + palm rejection with passing unit tests.
2. **M2**: Write a real stroke on a canvas with low latency + undo/redo.
3. **M3**: Notebooks/pages persist across restart.
4. **M4**: Full toolset + export.
5. **M5**: Polish + hardware validation.