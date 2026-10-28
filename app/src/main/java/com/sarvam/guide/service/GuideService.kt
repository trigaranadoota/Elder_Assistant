package com.sarvam.guide.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.sarvam.guide.R
import com.sarvam.guide.audio.AudioGate
import com.sarvam.guide.audio.WavUtil
import com.sarvam.guide.capture.ScreenCapturer
import com.sarvam.guide.core.Lang
import com.sarvam.guide.core.PowerPressDetector
import com.sarvam.guide.core.Scripts
import com.sarvam.guide.core.Tasks
import com.sarvam.guide.model.GuideStep
import com.sarvam.guide.net.GeminiVision
import com.sarvam.guide.net.Sar
import com.sarvam.guide.net.SarvamStt
import com.sarvam.guide.voice.TtsPlayer
import com.sarvam.guide.ui.MainActivity
import com.sarvam.guide.wake.VoskWakeEngine
import com.sarvam.guide.wake.WakeEngine
import com.sarvam.guide.wake.WakeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

object GuideHolder {
    @Volatile var projection: MediaProjection? = null
    @Volatile var pendingScreenRequest = false
    @Volatile var running = false
}

class GuideService : Service(), WakeListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val utterances = Channel<ByteArray>(capacity = 8)

    @Volatile
    private var projection: MediaProjection? = null

    private var powerPress: PowerPressDetector? = null
    private var wakeEngine: WakeEngine? = null
    private var mic: AudioGate? = null
    private var capturer: ScreenCapturer? = null
    private var ttsPlayer: TtsPlayer? = null
    private var busy = false

    @Volatile
    private var pendingTask: String? = null

    private val stt = SarvamStt()
    private val vision = GeminiVision()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GuideHolder.running = true
        Lang.init(this)
        createChannel()
        startAsForeground(FOREGROUND_MIC)
        ttsPlayer = TtsPlayer(this)

        mic = AudioGate(scope) { pcm -> onUtterance(pcm) }
        wakeEngine = VoskWakeEngine(this, this)

        powerPress = PowerPressDetector(this) { onWake() }.also { it.start() }
        runCatching { wakeEngine?.start() }
        mic?.setChunkSink(wakeEngine)
        mic?.start()
        scope.launch {
            delay(3000)
            if (!Sar.hasInternet(this@GuideService)) speakOffline()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i(TAG, "onStartCommand action=${intent?.action} busy=$busy")
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_WAKE -> if (!busy) scope.launch { session() }
            ACTION_PROJECTION -> onProjectionGranted(intent)
            ACTION_REMINDER -> onMedicineReminder(intent.getStringExtra(EXTRA_MED_NAME).orEmpty())
        }
        projection = GuideHolder.projection
        if (projection != null) {
            startAsForeground(FOREGROUND_MIC_AND_PROJECTION)
        }
        return START_STICKY
    }

    private fun onProjectionGranted(intent: Intent?) {
        if (GuideHolder.projection != null) return
        runCatching {
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_SCREEN_CAPTURE_INTENT, Intent::class.java)
            } else {
                intent?.getParcelableExtra(EXTRA_SCREEN_CAPTURE_INTENT)
            }

            // Upgrading the FGS type must happen BEFORE getMediaProjection on Android 14+.
            startAsForeground(FOREGROUND_MIC_AND_PROJECTION)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(Activity.RESULT_OK, data!!)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    android.util.Log.i(TAG, "projection stopped by system")
                    runCatching { capturer?.stop() }
                    capturer = null
                    projection = null
                    GuideHolder.projection = null
                }
            }, Handler(mainLooper))
            projection = proj
            GuideHolder.projection = proj
            // Create the virtual display NOW while the app is still visible;
            // Android 14+/Samsung refuse (or silently kill) displays created
            // from the background.
            ensureCapturer()
            if (!busy) {
                val pending = pendingTask
                pendingTask = null
                if (pending != null && capturer != null) {
                    // Consent was requested mid-task: continue it directly
                    // instead of asking the user what they wanted again.
                    android.util.Log.i(TAG, "projection granted; resuming $pending")
                    scope.launch { session(pending) }
                } else if (pending == null) {
                    scope.launch { session() }
                }
            }
        }.onFailure {
            android.util.Log.w(TAG, "projection grant failed", it)
        }
    }

    override fun onDestroy() {
        GuideHolder.running = false
        capturer?.stop()
        mic?.stop()
        wakeEngine?.release()
        powerPress?.stop()
        ttsPlayer?.stop()
        super.onDestroy()
    }

    override fun onWake() {
        android.util.Log.i(TAG, "onWake busy=$busy")
        if (!busy) scope.launch { session() }
    }

    private fun onMedicineReminder(name: String) {
        android.util.Log.i(TAG, "medicine reminder name='$name' busy=$busy")
        val text = Lang.t("med_notify") + if (name.isNotBlank()) " — $name" else ""
        updateNotification(text)
        if (!busy) playScript("medicine")
    }

    private fun onUtterance(pcm: ByteArray) {
        utterances.trySend(pcm)
    }

    private suspend fun session(presetTask: String? = null) {
        android.util.Log.i(TAG, "session start busy=$busy preset=${presetTask ?: "-"}")
        busy = true
        mic?.setChunkSink(null)
        try {
            if (!Sar.hasInternet(this)) {
                android.util.Log.w(TAG, "session: offline")
                speakOffline()
                return
            }
            // Only pay attention to speech said AFTER we finish asking.
            while (utterances.tryReceive().isSuccess) {}
            pendingTask = null
            var task = presetTask ?: hearTask()
            if (task.isEmpty()) return
            var completed = guide(task, withIntro = presetTask == null)
            // After a finished task keep listening for another request for a
            // while, without asking the user to press the button again.
            while (completed) {
                android.util.Log.i(TAG, "awaiting follow-up request (30s)")
                val pcm = withTimeoutOrNull(FOLLOWUP_LISTEN_MS) { utterances.receive() }
                if (pcm == null) {
                    // Stayed silent: shut everything down until the user
                    // starts the assistant again.
                    android.util.Log.i(TAG, "no follow-up within 30s; stopping service")
                    stopSelf()
                    break
                }
                val transcript = try {
                    transcribe(pcm, "followup.wav")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "STT failed (follow-up)", e)
                    break
                }
                android.util.Log.i(TAG, "follow-up transcript=$transcript")
                if (transcript.isNullOrBlank()) break
                val t = Tasks.match(transcript)
                if (t.isEmpty()) break
                task = if (t == Tasks.CALL_NEEDS_KIND) askCallKind() else t
                completed = guide(task, withIntro = false)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "session crashed unexpectedly", e)
            runCatching { speakProviderDown() }
        } finally {
            android.util.Log.i(TAG, "session end busy=false")
            mic?.setChunkSink(wakeEngine)
            wakeEngine?.reset()
            busy = false
        }
    }

    /** Asks what the user needs (twice max) and returns the matched task or "". */
    private suspend fun hearTask(): String {
        for (attempt in 0 until 2) {
            playScript(if (attempt == 0) "ask_what" else "couldnt_understand")
            // Drop anything the mic picked up while the prompt was playing
            // (the phone speaker can leak into the mic).
            delay(600)
            while (utterances.tryReceive().isSuccess) {}
            val pcm = withTimeoutOrNull(REPLY_TIMEOUT_MS) { utterances.receive() } ?: continue
            val transcript = try {
                transcribe(pcm, "ask.wav")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "STT failed", e)
                speakProviderDown()
                return ""
            }
            android.util.Log.i(TAG, "transcript=$transcript")
            if (transcript.isNullOrBlank()) continue
            val t = Tasks.match(transcript)
            if (t.isEmpty()) continue
            return if (t == Tasks.CALL_NEEDS_KIND) askCallKind() else t
        }
        return ""
    }

    private suspend fun transcribe(pcm: ByteArray, name: String): String {
        val wav = File(cacheDir, name)
        WavUtil.writeWav(wav, pcm)
        return stt.transcribe(wav, Lang.sttCode)
    }

    /** The user said "call" without a kind: ask normal or video, default to normal. */
    private suspend fun askCallKind(): String {
        playScript("ask_call_kind")
        delay(600)
        while (utterances.tryReceive().isSuccess) {}
        val pcm = withTimeoutOrNull(REPLY_TIMEOUT_MS) { utterances.receive() }
            ?: return Tasks.WHATSAPP_VOICE_CALL
        val transcript = try {
            transcribe(pcm, "kind.wav")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "STT failed (call kind)", e)
            return Tasks.WHATSAPP_VOICE_CALL
        }
        android.util.Log.i(TAG, "call kind transcript=$transcript")
        return Tasks.matchCallKind(transcript.orEmpty())
    }

    /** Runs the step loop; returns true only when the task was completed. */
    private suspend fun guide(task: String, withIntro: Boolean = true): Boolean {
        if (!ensureCapturer()) {
            // The projection token was consumed or revoked (Android 14+/Samsung);
            // ask for a fresh one, then continue the same task automatically.
            android.util.Log.i(TAG, "guide: need projection consent for $task")
            pendingTask = task
            playScript("consent")
            requestScreenConsent()
            return false
        }
        val callTask = task == Tasks.WHATSAPP_VIDEO_CALL || task == Tasks.WHATSAPP_VOICE_CALL
        var lastKey = ""
        var lastStep = -1
        var sameKeyTurns = 0
        var screenIssueSpoken = false
        // Announce which app this task needs, then send the user to the home
        // screen (the app icons live there; our own screen is in front now).
        if (withIntro) {
            val introKey = when (task) {
                Tasks.WHATSAPP_PHOTO -> "intro_photo"
                Tasks.WHATSAPP_VIDEO_CALL -> "intro_call"
                Tasks.WHATSAPP_VOICE_CALL -> "intro_voice"
                else -> "intro_play"
            }
            playScriptWait(introKey)
            playScriptWait("go_home")
        }
        lastKey = "go_home"
        repeat(MAX_TURNS) {
            if (!currentCoroutineContext().isActive) {
                android.util.Log.i(TAG, "guide exit: cancelled")
                return false
            }
            if (!Sar.hasInternet(this)) {
                android.util.Log.i(TAG, "guide exit: offline")
                speakOffline()
                delay(RETRY_MS)
                return false
            }
            val t0 = System.currentTimeMillis()
            val step = groundStep(task)
            android.util.Log.i(
                TAG,
                "groundStep=${step?.screen ?: "null"} risk=${step?.risk ?: "-"} done=${step?.done ?: false} in ${System.currentTimeMillis() - t0}ms",
            )
            if (step == null) {
                if (capturer == null && !ensureCapturer()) {
                    // Vision is unavailable and can't be revived; ask for a
                    // fresh screen-share token, then resume this task.
                    android.util.Log.i(TAG, "guide: no capturer, asking consent")
                    pendingTask = task
                    playScript("consent")
                    requestScreenConsent()
                    return false
                }
                if (!screenIssueSpoken) {
                    screenIssueSpoken = true
                    playScript("screen_issue")
                }
                delay(RETRY_MS)
                return@repeat
            }
            screenIssueSpoken = false
            if (step.risk == "payment" || step.risk == "permission") {
                android.util.Log.i(TAG, "guide exit: risk=${step.risk}")
                playScript("risk")
                return false
            }
            // Calls complete the moment the in-call screen appears, i.e. right
            // after the user taps the phone / camera icon.
            val onCallScreen =
                step.screen == "whatsapp_videocall" || step.screen == "whatsapp_voicecall"
            if (step.done || (callTask && onCallScreen)) {
                // Announce completion once and end the task; no second guess.
                android.util.Log.i(TAG, "guide exit: done")
                playScript(Scripts.DONE_KEYS[task] ?: "recovery")
                return true
            }
            val mapped = Scripts.keyFor(task, step.screen)
            val key = mapped ?: if (lastStep < 0) "go_home" else "recovery"
            // YouTube ends right after the search-bar tap: tell the user what to
            // search and finish (no need to watch what they pick).
            if (task == Tasks.YOUTUBE_PLAY && key == "play_search") {
                if (lastKey != "play_search") {
                    playScript("play_search")
                    delay(6000) // give them time to tap the magnifier
                    playScriptWait(Scripts.DONE_KEYS[task] ?: "play_done")
                    android.util.Log.i(TAG, "guide exit: youtube search reached")
                    return true
                }
                delay(VERIFY_MS)
                return@repeat
            }
            if (mapped != null && step.step < lastStep) {
                // Vision reported a screen from an earlier step (or flapped);
                // stay quiet rather than repeating older instructions.
                delay(VERIFY_MS)
                return@repeat
            }
            lastStep = step.step
            when {
                key == lastKey -> {
                    sameKeyTurns++
                    if (sameKeyTurns >= 3) {
                        playScript(key) // the person seems stuck on this step
                        sameKeyTurns = 0
                    }
                }
                else -> {
                    lastKey = key
                    sameKeyTurns = 0
                    playScript(key)
                }
            }
            delay(VERIFY_MS)
        }
        android.util.Log.i(TAG, "guide exit: turns exhausted")
        playScript("screen_issue")
        return false
    }

    private suspend fun groundStep(task: String): GuideStep? {
        return try {
            val bmp = capturer?.capture() ?: return null
            val bytes = ByteArrayOutputStream().use { os ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, os)
                os.toByteArray()
            }
            vision.ground(bytes, bmp.width, bmp.height, task, "image/jpeg")
        } catch (e: Exception) {
            null
        }
    }

    /** One VirtualDisplay per projection token; reused across sessions. */
    private fun ensureCapturer(): Boolean {
        if (capturer != null) return true
        val proj = GuideHolder.projection ?: return false
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val degrees = when (wm.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            capturer = ScreenCapturer().apply {
                start(proj, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, degrees)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "capturer start failed; requesting fresh consent", e)
            capturer = null
            false
        }
    }

    /** Plays a canned script line from assets; same mic discipline as speak(). */
    private fun playScript(key: String) {
        android.util.Log.i(TAG, "playScript=$key")
        mic?.setEnabled(false)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val release: () -> Unit = {
            if (armed.compareAndSet(false, true)) {
                scope.launch {
                    delay(400) // let the speaker echo die out before listening
                    mic?.setEnabled(true)
                }
            }
        }
        val mp = ttsPlayer?.playAsset(Scripts.audioPath(key)) { release() }
        val dur = mp?.duration ?: 0
        scope.launch {
            delay((dur + 2500L).coerceAtLeast(3000L))
            release()
        }
    }

    /** Plays a line and waits until it has finished (for back-to-back lines). */
    private suspend fun playScriptWait(key: String) {
        android.util.Log.i(TAG, "playScript=$key")
        mic?.setEnabled(false)
        val done = CompletableDeferred<Unit>()
        val mp = ttsPlayer?.playAsset(Scripts.audioPath(key)) { done.complete(Unit) }
        if (mp == null) done.complete(Unit)
        withTimeoutOrNull(((mp?.duration ?: 0) + 3000L).coerceAtLeast(4000L)) { done.await() }
        delay(400)
        mic?.setEnabled(true)
    }

    private fun reenableMic() {
        scope.launch { mic?.setEnabled(true) }
    }

    private fun speakProviderDown() {
        mic?.setEnabled(false)
        ttsPlayer?.playAsset(Scripts.audioPath("offline_provider_down"), this::reenableMic)
        scope.launch { delay(30000); mic?.setEnabled(true) }
    }

    private fun speakOffline() {
        val cause = if (Sar.airplaneMode(this)) "offline_airplane" else "offline_no_internet"
        mic?.setEnabled(false)
        ttsPlayer?.playAsset(Scripts.audioPath(cause), this::reenableMic)
        scope.launch { delay(30000); mic?.setEnabled(true) }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notify_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun startAsForeground(types: Int) {
        val n = buildNotification(Lang.t("notify_text"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, types)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(Lang.t("notify_title"))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    private fun requestScreenConsent() {
        GuideHolder.pendingScreenRequest = true
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i)
    }

    companion object {
        private const val TAG = "GuideService"
        private const val CHANNEL_ID = "guide"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.sarvam.guide.STOP"
        const val ACTION_WAKE = "com.sarvam.guide.WAKE"
        const val ACTION_PROJECTION = "com.sarvam.guide.PROJECTION"
        const val ACTION_REMINDER = "com.sarvam.guide.REMINDER"
        const val EXTRA_MED_NAME = "med_name"
        const val EXTRA_SCREEN_CAPTURE_INTENT = "screen_capture_intent"

        private val FOREGROUND_MIC: Int = run {
            var t = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            t
        }

        private val FOREGROUND_MIC_AND_PROJECTION: Int =
            FOREGROUND_MIC or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }

        private const val MAX_TURNS = 40
        private const val VERIFY_MS = 4000L
        private const val RETRY_MS = 8000L
        private const val REPLY_TIMEOUT_MS = 25000L
        private const val FOLLOWUP_LISTEN_MS = 30000L
    }
}