# Data Model

## Entities (Room)

```
Notebook
 ├── id : Long (PK)
 ├── title : String
 ├── isFavorite : Boolean
 ├── isArchived : Boolean
 ├── createdAt : Long (epoch millis)
 └── updatedAt : Long

Page
 ├── id : Long (PK)
 ├── notebookId : Long (FK → Notebook.id, indexed)
 ├── title : String
 ├── order : Int
 ├── backgroundType : String        // enum name: BLANK, RULED, NARROW_RULED, WIDE_RULED,
 │                                  //  GRID, SMALL_GRID, DOTTED, GRAPH, CORNELL, MUSIC, MATH
 ├── backgroundColor : Long
 ├── lineColor : Long
 ├── lineSpacingMm : Float
 ├── isInfinite : Boolean
 ├── viewportTransform : String     // JSON: zoom, offsetX, offsetY
 ├── contentBlob : String           // JSON: serialized PageContent
 ├── createdAt : Long
 └── updatedAt : Long

PageContent (serialized JSON stored in Page.contentBlob)
 ├── strokes : List<Stroke>
 ├── textObjects : List<TextObject>
 ├── imageObjects : List<ImageObject>
 └── shapeObjects : List<ShapeObject>

ImageObject
 ├── id, x, y, width, height, rotation, zOrder
 └── fileUri : String   // app-internal file reference (scoped storage compliant)

TextObject
 ├── id, x, y, width, height, rotation, zOrder
 ├── text : String
 ├── fontFamily : String, fontSizeMm : Float
 ├── bold, italic, underline : Boolean
 ├── color : Long, alignment : String

ShapeObject
 ├── id, x, y, rotation, zOrder
 ├── shapeType : LINE, ARROW, RECT, ROUNDED_RECT, CIRCLE, ELLIPSE,
 │             TRIANGLE, POLYGON, STAR
 ├── points : List<Point>   // geometry
 ├── strokeWidthMm : Float, color : Long
 ├── fillColor : Long?, fillEnabled : Boolean

Stroke (see drawing-engine.md)
 └── packed point FloatArray serialized as compact JSON
```

## Design Decisions

### Why page-level blobs?
A page's drawing content is write-heavy (one stroke commit per pen-up). Storing the
serialized page as a single blob in the `Page` row gives:
- Atomic page updates (one transaction),
- Fast page load (one row read),
- Simple, versioned serialization via kotlinx.serialization.

Notebooks remain relational for listing/ordering/favoriting/archiving. This is the right
trade-off for a drawing app; the *content* is document-like, the *catalog* is relational.

### Serialization
- Stroke points packed as a flat `FloatArray` → compact base array JSON.
- `PageContent` uses a `@Serializable` sealed model with `type` discriminator so future
  object kinds can be added with `@SerialName` versioning.
- A `contentVersion: Int` field migrates old blobs; unknown fields are preserved
  (ignore-unknown-keys) to avoid data loss.

### Files
Image objects reference files under `context.filesDir/note-media/` — app-private storage,
scoped-storage compliant, no permissions needed. Exports write via `ACTION_CREATE_DOCUMENT`
(Saf) so the user chooses the destination.

## Repository

```
NotesRepository
 ├── notebooks : Flow<List<Notebook>>
 ├── pagesFor(notebookId) : Flow<List<Page>>
 ├── loadPage(id) : Page?
 ├── savePage(page)          // atomic upsert, background dispatcher
 ├── updateNotebook(...)
 ├── createNotebook / deleteNotebook / duplicateNotebook
 └── export helpers
```

Mutations update an in-memory cache synchronously where the UI needs immediate
consistency, then persist on `Dispatchers.IO` with a debounce. All DB work is off the
main thread.

## Migration & Integrity

- Room migrations add columns/entities version by version.
- Page writes are serialized through a per-notebook write queue so two concurrent saves
  cannot interleave partial blobs.
- Recovery: writes go through "write temp → fsync → atomic rename". A crash mid-write
  leaves the previous good blob intact.
- `updatedAt` is bumped on every commit for external backup tools and search indexing.