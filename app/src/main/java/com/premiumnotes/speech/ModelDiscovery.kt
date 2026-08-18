package com.premiumnotes.speech

import android.content.Context
import java.io.File

/**
 * Resolves a Vosk model directory for on-device transcription.
 *
 * Priority:
 *  1. A model extracted/installed under `filesDir/vosk/model/` (first launch copies the
 *     build-time bundled asset there; users can also drop a bigger model in manually).
 *  2. A model bundled in assets under `vosk/model/` (populated by the `downloadVoskModel`
 *     Gradle task at build time) — extracted to app-private files on first use so it's
 *     fully offline, F-Droid friendly, with no mandatory runtime download.
 *
 * Vosk's Android Model class takes a real filesystem path (no assets-direct constructor),
 * so extraction is required. Returns null when no model is present; the UI then disables
 * Classroom Notes with a clear message instead of failing at runtime.
 */
object ModelDiscovery {

    private const val ASSETS_MODEL = "vosk/model"
    private const val FILES_MODEL_RELATIVE = "vosk/model"

    fun isBundledInAssets(context: Context): Boolean =
        context.assets.list(ASSETS_MODEL)?.any { it == "am" } == true

    fun isInstalledInFiles(context: Context): Boolean {
        val dir = File(context.filesDir, FILES_MODEL_RELATIVE)
        return File(dir, "am").isDirectory && File(dir, "graph").isDirectory
    }

    /** Returns the filesystem path to pass to the Vosk [org.vosk.Model] constructor, or null. */
    fun resolve(context: Context): String? {
        if (isInstalledInFiles(context)) {
            return File(context.filesDir, FILES_MODEL_RELATIVE).absolutePath
        }
        if (isBundledInAssets(context)) {
            val target = File(context.filesDir, FILES_MODEL_RELATIVE)
            copyAssetTree(context.assets, ASSETS_MODEL, target)
            return target.absolutePath
        }
        return null
    }

    private fun copyAssetTree(assets: android.content.res.AssetManager, prefix: String, target: File) {
        if (target.exists()) return
        val children = assets.list(prefix).orEmpty()
        for (child in children) {
            val assetPath = "$prefix/$child"
            val out = File(target, child)
            if (assets.list(assetPath) != null) {
                copyAssetTree(assets, assetPath, out)
            } else {
                out.parentFile?.mkdirs()
                assets.open(assetPath).use { it.copyTo(out.outputStream()) }
            }
        }
    }
}