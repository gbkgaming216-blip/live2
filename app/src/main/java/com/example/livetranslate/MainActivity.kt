package com.example.livetranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        TextView(this).apply {
            text = "Live Translate\nSuara video (English) → teks Indonesia"
            textSize = 22f
            gravity = Gravity.CENTER
        }.also { root.addView(it) }

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }.also { root.addView(it) }

        btn("1. Izinkan tampil di atas aplikasi lain") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btn("2. Mulai terjemahan") { start() }
        btn("Berhenti") {
            stopService(Intent(this, OverlayService::class.java))
            toast("Dihentikan")
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = "Overlay: " + (if (Settings.canDrawOverlays(this)) "diizinkan ✓" else "belum diizinkan") +
            "\nIzin audio: " + (if (hasMic()) "diizinkan ✓" else "belum diizinkan")
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (!Settings.canDrawOverlays(this)) { toast("Izinkan overlay dulu (tombol 1)"); return }
        if (!hasMic() || (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
            return
        }
        // Dialog sistem ini wajib agar aplikasi boleh menangkap suara internal HP.
        // Aplikasi ini TIDAK merekam layar.
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasMic()) start() else toast("Izin audio dibutuhkan")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                toast("Berjalan. Buka video lalu putar.")
                moveTaskToBack(true)
            } else toast("Izin penangkapan suara ditolak")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
