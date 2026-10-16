package com.example.virtualmouse

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var btnToggle: TextView

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnToggle = findViewById(R.id.btn_toggle)

        findViewById<TextView>(R.id.btn_overlay).setOnClickListener {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<TextView>(R.id.btn_access).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<TextView>(R.id.btn_ime_enable).setOnClickListener {
            open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<TextView>(R.id.btn_ime_pick).setOnClickListener {
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show()
            }
        }
        btnToggle.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
            } else if (overlayOk() && accessOk()) {
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            } else {
                Toast.makeText(this, R.string.toast_need_perms, Toast.LENGTH_LONG).show()
            }
            handler.postDelayed({ refresh() }, 400)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_no_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun overlayOk() = Settings.canDrawOverlays(this)

    private fun accessOk(): Boolean {
        if (MouseAccessibilityService.instance != null) return true
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val enabledServices = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val cn = ComponentName(this, MouseAccessibilityService::class.java)
            val foundInAm = enabledServices?.any {
                it.resolveInfo?.serviceInfo?.packageName == packageName &&
                        (it.resolveInfo?.serviceInfo?.name == cn.className || it.id?.contains(packageName) == true)
            } ?: false
            if (foundInAm) return true

            val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            list?.split(':')?.any {
                it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun imeEnabled(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.any { it.packageName == packageName } ?: false
        } catch (_: Exception) {
            try {
                val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
                s?.contains(packageName) == true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun imeSelected(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (Build.VERSION.SDK_INT >= 34) {
                imm?.currentInputMethodInfo?.packageName == packageName
            } else {
                val s = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                s?.startsWith("$packageName/") == true
            }
        } catch (_: Exception) {
            try {
                val s = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                s?.startsWith("$packageName/") == true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun mark(id: Int, ok: Boolean, text: Int) {
        findViewById<TextView>(id).apply {
            this.text = (if (ok) "✅ " else "❌ ") + getString(text)
            setTextColor(if (ok) 0xFF00FF88.toInt() else 0xFFFF6677.toInt())
        }
    }

    private fun refresh() {
        val ov = overlayOk()
        val ac = accessOk()
        val running = OverlayService.isRunning
        mark(R.id.tv_status_overlay, ov, R.string.st_overlay)
        mark(R.id.tv_status_access, ac, R.string.st_access)
        mark(R.id.tv_status_ime_enabled, imeEnabled(), R.string.st_ime_enabled)
        mark(R.id.tv_status_ime_selected, imeSelected(), R.string.st_ime_selected)
        mark(R.id.tv_status_running, running, R.string.st_running)
        btnToggle.setText(if (running) R.string.btn_stop else R.string.btn_start)
        val enabled = running || (ov && ac)
        btnToggle.isEnabled = enabled
        btnToggle.alpha = if (enabled) 1f else 0.4f
    }
}
