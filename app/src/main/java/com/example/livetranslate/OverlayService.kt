package com.example.livetranslate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var box: LinearLayout? = null
    private lateinit var tvId: TextView
    private lateinit var tvEn: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var translator: Translator? = null
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var started = false
    @Volatile private var translatorReady = false
    private var lastEn = ""
    private var lastTranslateAt = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        started = true

        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        setupTranslator()

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")

        if (data == null) { ui("Izin penangkapan suara tidak valid."); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)

        running = true
        worker = Thread { runPipeline() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("lt", "Live Translate", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "lt")
            .setContentTitle("Live Translate aktif")
            .setContentText("Menerjemahkan suara HP ke Indonesia")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupTranslator() {
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
        )
        translator = t
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translatorReady = true }
            .addOnFailureListener { ui("Gagal mengunduh model terjemahan. Cek internet.") }
    }

    private fun buildOverlay() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(205, 0, 0, 0)); cornerRadius = dp(16).toFloat()
            }
        }
        val close = TextView(this).apply {
            text = "✕  tutup"
            setTextColor(Color.LTGRAY); textSize = 12f
            gravity = Gravity.END
            setOnClickListener { stopSelf() }
        }
        tvId = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = "Memulai…"
        }
        tvEn = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        b.addView(close); b.addView(tvId); b.addView(tvEn)

        params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.04).toInt()
            y = (resources.displayMetrics.heightPixels * 0.65).toInt()
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt()
                    params.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(b, params)
                }
            }
            true
        }
        wm.addView(b, params)
        box = b
    }

    private fun ui(msg: String) { handler.post { if (box != null) tvId.text = msg } }

    private fun show(en: String, final: Boolean) {
        val now = System.currentTimeMillis()
        if (!final && (now - lastTranslateAt < 600 || en == lastEn)) return
        lastTranslateAt = now
        lastEn = en
        handler.post {
            if (box == null) return@post
            tvEn.text = en
            if (translatorReady) {
                translator?.translate(en)?.addOnSuccessListener { tvId.text = it }
            }
        }
    }

    // ---------- Model suara offline (Vosk, English) ----------

    private fun ensureModel(): File {
        val root = File(filesDir, "vosk")
        val dir = File(root, "vosk-model-small-en-us-0.15")
        if (File(dir, "am/final.mdl").exists()) return dir

        ui("Mengunduh model suara (~40 MB), hanya sekali…")
        root.mkdirs()
        val zip = File(cacheDir, "model.zip")
        val conn = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection()
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.getInputStream().use { input -> zip.outputStream().use { input.copyTo(it) } }

        ui("Mengekstrak model…")
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("bad zip")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
        return dir
    }

    // ---------- Tangkap suara internal -> kenali -> terjemahkan ----------

    @SuppressLint("MissingPermission")
    private fun runPipeline() {
        try {
            val modelDir = ensureModel()
            ui("Memuat model suara…")
            val model = Model(modelDir.absolutePath)
            val recognizer = Recognizer(model, 16000.0f)

            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, 19200))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            audioRecord = rec
            rec.startRecording()
            ui("Siap. Putar videonya…")

            val buf = ShortArray(4800)          // 100 ms @ 48 kHz
            val out = ByteArray(1600 * 2)       // 100 ms @ 16 kHz
            var everHeard = false
            var reads = 0

            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(20); continue }

                var peak = 0
                val m = n / 3
                for (i in 0 until m) {
                    val s = (buf[i * 3] + buf[i * 3 + 1] + buf[i * 3 + 2]) / 3
                    if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s)
                    out[2 * i] = (s and 0xff).toByte()
                    out[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                if (peak > 0) everHeard = true
                reads++
                if (!everHeard && reads == 200) {
                    ui("Belum ada suara tertangkap. Pastikan video berbunyi, atau aplikasi videonya memblokir perekaman suara (mis. Netflix).")
                }

                if (recognizer.acceptWaveForm(out, m * 2)) {
                    val t = JSONObject(recognizer.result).optString("text")
                    if (t.isNotBlank()) show(t, true)
                } else {
                    val p = JSONObject(recognizer.partialResult).optString("partial")
                    if (p.isNotBlank()) show(p, false)
                }
            }
            recognizer.close()
            model.close()
        } catch (e: Throwable) {
            ui("Error: ${e.message}")
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        audioRecord?.let { runCatching { it.stop() }; runCatching { it.release() } }
        runCatching { projection?.stop() }
        translator?.close()
        box?.let { runCatching { wm.removeView(it) } }
        box = null
        super.onDestroy()
    }
}
