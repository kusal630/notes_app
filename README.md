# ✒️ Vellum

**Private, fully offline handwriting notes for Android — with a software palm-rejection pipeline at its core.**

Write naturally with a stylus or your finger. Raw touch input is analyzed in real time to reliably ignore palm rests, so only your pen or writing finger leaves ink — no proprietary or cloud service required. Everything stays on your device: notes, ink, images, transcripts.

> ⬇️ **Download:** grab the latest APK from [**Releases**](https://github.com/kusal630/vellum/releases) (`vellum-v1.2.0-debug.apk`, debug-signed, ~102MB — includes the on-device speech model).

## ✨ Features

| Area | What you get |
|---|---|
| ✍️ Handwriting | Low-latency ink, pen + highlighter, 6 pen styles, one-tap **Smooth** cleanup |
| 🌴 Palm rejection | Passive-stylus + finger engine: write with your palm resting — second-touch handoff, palm-sized lock rescue, writing-hand posture (right/left/two-handed edge bias), Low/Med/High sensitivity presets, saturated-pressure palm confirmation, per-device calibration + Labs screen. Acceptance-tested: palm-first, mid-stroke palm, no-palm, and continued writing all keep inking |
| 🧰 Toolbar | Floating pills up top (navigate/undo, tools, page actions), tap the active pen for colors & thickness, left color rail with quick dots + full palette — never under the palm |
| 🔤 Text & images | Text boxes (insert + edit), photo insertion, move / resize / duplicate |
| 🔷 Shapes | Line, arrow, rect, circle, ellipse, triangle, star, hexagon — select, move, resize |
| 📄 Pages | Multi-page notebooks, page rail, long-press **duplicate / delete**, templates per notebook + per page |
| 📚 Paper | 11 templates (ruled, grid, dotted, graph, Cornell, music, math…), 10 premium covers incl. Aurum Gold |
| 🕘 History | Page snapshots on close + on demand (deduped, newest 20 kept), restore with pre-restore safety snapshot, per-page history from the page rail |
| 🔍 Organize | Noteshelf home (sidebar + categories, Starred/Unfiled/Trash/Archived), tags with filter chips, full-text search over titles/typed text/transcripts/summaries, Quick Note, Recent / A–Z sort |
| 💾 Backup | One-tap local ZIP export (database + images + PDF pages) to your own folder — no cloud. Passphrase-encrypted backups (AES-256-GCM) + in-app restore with safety copy; the passphrase is never stored |
| 🔄 Sync | Device sync via your Syncthing folder: versioned snapshots (plain/encrypted) with manifests, explicit newest-wins import with safety copy — no accounts, no servers |
| 📥 PDF | Import & annotate any PDF offline; export preserves italic/underline/alignment; reader themes + auto-trim coming |
| 📖 Read mode | Read imported books full-screen (Original/Sepia/Night), freehand highlighter in 4 colors, tap-erase, every highlight saved per page + searchable review list that jumps back to the exact page |
| 🎙️ Classroom | Optional on-device recording with live Vosk transcription + summary, saved with the note |
| 🎨 Canvas | Infinite canvas, pinch-to-zoom + pan, undo/redo (incl. two-finger double-tap), viewport-culled display list (off-screen ink skipped, zero-allocation draw path, surgical partial invalidation while writing), no auto-scroll (viewport moves only by pan/zoom/scroll bar), light & dark themes |
| ✍️ Convert | Select ink → **Convert** to an editable text box (single undo step; on-device $1-style print recognition, A–Z/0–9, "?" marks low confidence) |
| 🧪 Diagnostics | Input Labs screen for calibrating palm rejection to your hardware |

## 🔒 Privacy

- **100% offline** — Room database on-device; transcription runs on-device (bundled Vosk model). Nothing ever leaves the phone.
- **No internet permission**, no accounts, no tracking, no ads, no analytics.
- Only **one optional permission**: `RECORD_AUDIO`, asked only when you start a classroom recording. The app works fully without it.
- `allowBackup` is **disabled** — notes are never uploaded to cloud backups.
- Hardened storage: import size caps (25MB images / 50-page PDFs), path-traversal-safe media resolution, sampled bitmap decoding.

## 🚀 Quick start

1. Download the APK from [Releases](https://github.com/kusal630/vellum/releases) and install it.
2. Tap **+** to create a notebook (Normal or Classroom), pick a cover + paper template.
3. Write. Rest your palm — rejection is on by default. Open **Labs** to calibrate.
4. Long-press pages in the rail to duplicate/delete; use Select → **Smooth** to clean up ink.

## 🛠️ Building

Requirements: JDK 17+, Android SDK (compileSdk 35, minSdk 26, targetSdk 35). The wrapper fetches Gradle 8.13 automatically.

```sh
# Debug APK (signed, installable)
./gradlew :app:assembleDebug

# Release APK (unsigned; F-Droid signs its own builds)
./gradlew :app:assembleRelease

# Unit tests (327 passing)
./gradlew :app:testDebugUnitTest
```

Release builds are reproducible: two clean builds from the same commit produce byte-for-byte identical APKs.

### Speech model for Classroom Notes

Classroom Notes uses the small English Vosk model, downloaded once at build time (~40MB) and bundled into the APK — fully offline, no runtime downloads:

```sh
./gradlew :app:downloadVoskModel
```

The model is not committed to the repository. Builds without it still succeed; the app then disables Classroom Notes with an explanatory message.

## 🗺️ Roadmap

- Auto shape recognition + handwriting alignment
- Tags + full-text search (Room FTS)
- Versioned ZIP backup + scheduled local auto-backup
- Home-screen widgets + app shortcuts
- Voice canvas commands, spotlight focus mode
- Audio-note sync replay

## 📄 License

Copyright (C) 2026 codeRed — GPL-3.0-or-later. See [LICENSE](LICENSE).
