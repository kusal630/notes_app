package com.premiumnotes.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.premiumnotes.MainActivity
import com.premiumnotes.R

/**
 * Foreground service that records class audio and runs it through the on-device Vosk
 * recognizer. The recognizer and all audio I/O live on background threads so the drawing
 * pipeline is never blocked; recognized segments are pushed to [SpeechController], which
 * the editor UI persists into the page.
 */
class AudioCaptureService : Service() {

    private var captureThread: Thread? = null
    private var recognizer: VoskSpeechToText? = null
    private var startedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val pageId = intent?.getLongExtra(EXTRA_PAGE_ID, -1L)
                if (pageId != null && pageId > 0) startRecording(pageId)
            }
        }
        return START_STICKY
    }

    private fun startRecording(pageId: Long) {
        if (captureThread?.isAlive == true) return
        // Defensive re-check: the UI requests the microphone permission before starting
        // the service, but the user may revoke it while the service is running.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val model = ModelDiscovery.resolve(this)
        if (model == null) {
            // Model missing: nothing to recognize. Stop immediately; the UI should have
            // disabled recording before calling us, but degrade gracefully either way.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val rec = VoskSpeechToText(this)
        recognizer = rec
        SpeechController.beginRecording(pageId)
        startedAtMs = System.currentTimeMillis()
        startAsForeground()
        captureThread = Thread({ runCapture(rec) }, "vosk-capture").also { it.start() }
    }

    private fun startAsForeground() {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Classroom Notes recording")
            .setContentText("Transcribing on-device…")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun runCapture(rec: VoskSpeechToText) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val bufferSize = AudioRecord.getMinBufferSize(
            VoskSpeechToText.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096) * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            VoskSpeechToText.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return
            record.startRecording()
            val buf = ShortArray(bufferSize / 2)
            while (!Thread.currentThread().isInterrupted) {
                val read = record.read(buf, 0, buf.size)
                if (read > 0) {
                    val result = rec.accept(buf, read)
                    SpeechController.setPartial(result.partial)
                    result.final?.let { text ->
                        if (text.isNotBlank()) {
                            val now = System.currentTimeMillis() - startedAtMs
                            SpeechController.addSegment(text, (now - 1500L).coerceAtLeast(0L), now)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Recording failure (mic in use, permission revoked): end the session cleanly.
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            rec.close()
        }
    }

    private fun stopRecording() {
        captureThread?.interrupt()
        captureThread = null
        SpeechController.endRecording()
        recognizer = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Classroom recording",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "classroom_recording"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.premiumnotes.action.STOP_RECORDING"
        const val EXTRA_PAGE_ID = "page_id"

        fun start(context: Context, pageId: Long) {
            val intent = Intent(context, AudioCaptureService::class.java)
                .putExtra(EXTRA_PAGE_ID, pageId)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}