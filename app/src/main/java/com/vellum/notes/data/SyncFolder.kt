package com.vellum.notes.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF-tree file access for the Syncthing-mirrored sync folder. Framework APIs
 * only (no new dependencies): lists snapshot ZIPs + sidecars, reads payloads,
 * writes snapshots. Persistable URI permissions are taken by the UI when the
 * folder is picked.
 */
object SyncFolder {

    data class DocFile(
        val uri: Uri,
        val name: String,
        val lastModified: Long,
        val size: Long,
    )

    fun listFiles(resolver: ContentResolver, treeUri: Uri): List<DocFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val out = ArrayList<DocFile>()
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )
            cursor?.let { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val modCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val docId = c.getString(idCol)
                    out += DocFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = c.getString(nameCol),
                        lastModified = if (modCol >= 0) c.getLong(modCol) else 0L,
                        size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                    )
                }
            }
        } catch (t: Throwable) {
            // Unreadable folder (permission lost, tree gone): caller shows empty.
        } finally {
            cursor?.close()
        }
        return out
    }

    fun readBytes(resolver: ContentResolver, docUri: Uri): ByteArray? {
        return try {
            resolver.openInputStream(docUri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Lists snapshots in the folder with their manifests resolved.
     * [readText] reads a sidecar file's bytes (tested seam).
     */
    fun collectSnapshots(
        files: List<DocFile>,
        readText: (DocFile) -> ByteArray?,
    ): List<SyncSnapshot.SnapshotMeta> {
        val byName = files.associateBy { it.name }
        val out = ArrayList<SyncSnapshot.SnapshotMeta>()
        for (file in files) {
            val parsed = SyncSnapshot.parseFileName(file.name) ?: continue
            val manifestBytes = byName[SyncSnapshot.manifestName(file.name)]
                ?.let(readText)
                ?.toString(Charsets.UTF_8)
            val manifest = manifestBytes?.let(SyncSnapshot::manifestFromJson)
            out += SyncSnapshot.SnapshotMeta(
                deviceId = manifest?.deviceId ?: parsed.first,
                createdAt = manifest?.createdAt ?: parsed.second,
                fileName = file.name,
                manifest = manifest,
            )
        }
        return out
    }

    fun writeFile(
        context: Context,
        treeUri: Uri,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            // Replace any previous file with the same name (same-device retries).
            deleteByName(context.contentResolver, treeUri, name)
            val docUri = DocumentsContract.createDocument(
                context.contentResolver, parent, mime, name,
            ) ?: return false
            context.contentResolver.openOutputStream(docUri)?.use { it.write(bytes) }
                ?: return false
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun deleteByName(resolver: ContentResolver, treeUri: Uri, name: String) {
        try {
            listFiles(resolver, treeUri)
                .firstOrNull { it.name == name }
                ?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
        } catch (t: Throwable) {
            // Best effort.
        }
    }
}
