package com.premiumnotes.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device speech test: loads the bundled Vosk model (extracted to app
 * storage by [ModelDiscovery]), feeds a known 16 kHz mono PCM16 WAV through the same
 * [VoskSpeechToText] recognizer the foreground recording service uses, and asserts that
 * real words come out.
 *
 * Requires the model: run `./gradlew downloadVoskModel` first (the asset is not
 * committed to git).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTranscriptionTest {

    @Test
    fun recognizesSpeechFromBundledModel() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val modelPath = ModelDiscovery.resolve(context)
        assertNotNull("Vosk model must be bundled (run ./gradlew downloadVoskModel)", modelPath)

        val stt = VoskSpeechToText(context)
        try {
            // The WAV lives in the androidTest APK's assets, not the app's.
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets
                .open("test.wav").use { it.readBytes() }
            val samples = ShortArray((bytes.size - 44) / 2)
            for (i in samples.indices) {
                val b = i * 2 + 44
                samples[i] = ((bytes[b].toInt() and 0xff) or (bytes[b + 1].toInt() shl 8)).toShort()
            }

            val finals = mutableListOf<String>()
            var partial = ""
            val chunk = 8000
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunk, samples.size)
                val r = stt.accept(samples.copyOfRange(offset, end), end - offset)
                if (r.partial.isNotBlank()) partial = r.partial
                r.final?.takeIf { it.isNotBlank() }?.let { finals += it }
                offset = end
            }

            val text = (finals + partial).joinToString(" ")
            assertTrue("recognized text should be non-empty, got: [$text]", text.isNotBlank())
            assertTrue(
                "expected recognizable speech, got: [$text]",
                text.split(" ").size >= 2,
            )
        } finally {
            stt.close()
        }
    }
}
