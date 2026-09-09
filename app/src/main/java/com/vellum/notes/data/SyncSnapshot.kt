package com.vellum.notes.data

import org.json.JSONObject

/**
 * Syncthing-folder sync metadata (pure logic, JVM-testable).
 *
 * Sync itself is deliberately file-based: Vellum writes versioned snapshot
 * ZIPs (plain or passphrase-encrypted, same bytes as backups) plus a JSON
 * sidecar into a folder the user's Syncthing mirrors. No accounts, no
 * servers, no new permissions — Syncthing owns the transport (TLS) while the
 * optional passphrase owns storage-level end-to-end privacy. Imports are
 * explicit (newest snapshot wins, safety copy first) because SQLite files
 * cannot be auto-merged.
 */
object SyncSnapshot {

    data class Manifest(
        val deviceId: String,
        val createdAt: Long,
        val notebookCount: Int,
        val pageCount: Int,
        val encrypted: Boolean,
        val backupVersion: Int = BackupManager.BACKUP_VERSION,
    )

    data class SnapshotMeta(
        val deviceId: String,
        val createdAt: Long,
        val fileName: String,
        val manifest: Manifest?,
    )

    private val NAME_PATTERN = Regex("^vellum-snapshot-(.+)-(\\d+)\\.zip$")

    /** Snapshot ZIP name for this device and timestamp. */
    fun fileName(deviceId: String, timestampMs: Long): String =
        "vellum-snapshot-$deviceId-$timestampMs.zip"

    /** Sidecar name for a snapshot ZIP. */
    fun manifestName(zipName: String): String = "$zipName.json"

    /** Parses (deviceId, timestamp) from a snapshot file name, or null. */
    fun parseFileName(name: String): Pair<String, Long>? {
        val match = NAME_PATTERN.matchEntire(name) ?: return null
        val timestamp = match.groupValues[2].toLongOrNull() ?: return null
        return match.groupValues[1] to timestamp
    }

    fun manifestToJson(manifest: Manifest): String =
        JSONObject()
            .put("deviceId", manifest.deviceId)
            .put("createdAt", manifest.createdAt)
            .put("notebookCount", manifest.notebookCount)
            .put("pageCount", manifest.pageCount)
            .put("encrypted", manifest.encrypted)
            .put("backupVersion", manifest.backupVersion)
            .toString()

    fun manifestFromJson(raw: String): Manifest? {
        return try {
            val json = JSONObject(raw)
            Manifest(
                deviceId = json.getString("deviceId"),
                createdAt = json.getLong("createdAt"),
                notebookCount = json.optInt("notebookCount", 0),
                pageCount = json.optInt("pageCount", 0),
                encrypted = json.optBoolean("encrypted", false),
                backupVersion = json.optInt("backupVersion", BackupManager.BACKUP_VERSION),
            )
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Newest snapshot per device, excluding this device's own snapshots and
     * anything unparsable, newest first.
     */
    fun inboundSnapshots(
        files: List<SnapshotMeta>,
        ownDeviceId: String,
    ): List<SnapshotMeta> =
        files
            .filter { it.deviceId != ownDeviceId }
            .groupBy { it.deviceId }
            .mapNotNull { (_, group) -> group.maxByOrNull { it.createdAt } }
            .sortedByDescending { it.createdAt }

    /** Short human label for a device id (first 8 chars). */
    fun shortDeviceId(deviceId: String): String = deviceId.take(8)

    /**
     * Conflict detection (pure, JVM-testable).
     *
     * A conflict exists when BOTH sides changed since the last successful
     * sync: the newest inbound snapshot is newer than [lastSyncAtMs] AND the
     * local notes were modified after [lastSyncAtMs]. Either side quiet means
     * a clean fast-forward (no dialog). Returns the newest inbound snapshot
     * that conflicts, or null.
     *
     * @param lastSyncAtMs last successful sync timestamp, null when never synced.
     * @param localModifiedAtMs local modification timestamp, null when unknown/clean.
     * @param inbound inbound snapshots newest-first (see [inboundSnapshots]).
     */
    fun detectConflict(
        lastSyncAtMs: Long?,
        localModifiedAtMs: Long?,
        inbound: List<SnapshotMeta>,
    ): SnapshotMeta? {
        if (inbound.isEmpty()) return null
        val newest = inbound.maxByOrNull { it.createdAt } ?: return null
        // Never synced: first inbound is an import candidate, not a conflict.
        // Local timestamp unknown: cannot prove local changes, stay quiet.
        if (lastSyncAtMs == null || localModifiedAtMs == null) return null
        if (localModifiedAtMs <= lastSyncAtMs) return null
        if (newest.createdAt <= lastSyncAtMs) return null
        return newest
    }
}
