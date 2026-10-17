package com.example.virtualmouse

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * Главный экран: интерфейс рисует HTML (assets/virtual-mouse.html) в WebView.
 * Страница вызывает методы моста Android.* (overlay, accessibility, enableKeyboard, pickKeyboard, stop),
 * а приложение передаёт в неё статусы через window.setState({...}).
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private var pageReady = false
    private var userStopped = false

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private inner class Bridge {
        @JavascriptInterface fun overlay() { handler.post { onOverlay() } }
        @JavascriptInterface fun accessibility() { handler.post { onAccessibility() } }
        @JavascriptInterface fun enableKeyboard() { handler.post { onEnableKeyboard() } }
        @JavascriptInterface fun pickKeyboard() { handler.post { onPickKeyboard() } }
        @JavascriptInterface fun stop() { handler.post { onStopToggle() } }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Цвет системных панелей под фон страницы
        window.statusBarColor = Color.parseColor("#A9B8E6")
        window.navigationBarColor = Color.parseColor("#A9B8E6")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        web = findViewById(R.id.main_web)
        web.setBackgroundColor(Color.parseColor("#A9B8E6"))
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                refresh()
            }
        }
        web.loadUrl("file:///android_asset/virtual-mouse.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        refresh()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            web.removeJavascriptInterface("Android")
            web.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    // ---------- Действия кнопок страницы ----------

    private fun onOverlay() =
        open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    private fun onAccessibility() = open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun onEnableKeyboard() = open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    private fun onPickKeyboard() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    /** «Стоп»: если оверлей работает — останавливает; если остановлен — запускает снова. */
    private fun onStopToggle() {
        if (OverlayService.isRunning) {
            userStopped = true
            stopService(Intent(this, OverlayService::class.java))
        } else if (overlayOk() && accessOk()) {
            userStopped = false
            startOverlay()
        } else {
            Toast.makeText(this, R.string.toast_need_perms, Toast.LENGTH_LONG).show()
        }
        handler.postDelayed({ refresh() }, 400)
    }

    private fun startOverlay() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_need_perms, Toast.LENGTH_LONG).show()
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_no_settings, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Проверка статусов ----------

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

    /** Передаёт текущие статусы в страницу; при выданных разрешениях сам запускает оверлей. */
    private fun refresh() {
        val ov = overlayOk()
        val ac = accessOk()
        val ime = imeEnabled()
        val sel = imeSelected()

        if (ov && ac && !userStopped && !OverlayService.isRunning) {
            startOverlay()
            handler.postDelayed({ pushState(ov, ac, ime, sel) }, 400)
        }
        pushState(ov, ac, ime, sel)
    }

    private fun pushState(ov: Boolean, ac: Boolean, ime: Boolean, sel: Boolean) {
        if (!pageReady) return
        val js = "window.setState({overlay:$ov,accessibility:$ac,enableKeyboard:$ime," +
                "pickKeyboard:$sel,running:${OverlayService.isRunning}})"
        web.evaluateJavascript(js, null)
    }
}
