package com.niniss.luaexecutor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var bubble: TextView? = null
    private var web: WebView? = null
    private var panelOn = false
    private var bubbleOn = false
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var bubbleLp: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            removeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        if (panel == null) createViews()
        expand()
        return START_STICKY
    }

    override fun onDestroy() {
        removeAll()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "Lua Executor", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Lua Executor")
            .setContentText("Appuie pour ouvrir la fenêtre")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fermer", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createViews() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels

        // ---- Fenêtre principale ----
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#0f0f0f"))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.parseColor("#2a2a2a"))
        }
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            clipToOutline = true
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a1a"))
            setPadding(dp(12), 0, dp(4), 0)
        }
        val title = TextView(this).apply {
            text = "⠿  Lua Executor"
            setTextColor(Color.parseColor("#00c853"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        bar.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.gravity = Gravity.CENTER_VERTICAL })
        title.gravity = Gravity.CENTER_VERTICAL

        fun barBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
        bar.addView(barBtn("–") { minimize() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        bar.addView(barBtn("✕") { removeAll(); stopSelf() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        p.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        val w = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0f0f0f"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/index.html")
        }
        web = w
        p.addView(w, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        panelLp = WindowManager.LayoutParams(
            (sw * 0.92).toInt(), (sh * 0.6).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - width) / 2
            y = dp(60)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = p
        drag(bar, p, panelLp, null)

        // ---- Bulle réduite ----
        val b = TextView(this).apply {
            text = "L"
            setTextColor(Color.BLACK)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00c853"))
            }
        }
        bubbleLp = WindowManager.LayoutParams(
            dp(56), dp(56), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }
        bubble = b
        drag(b, b, bubbleLp) { expand() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(handle: View, target: View, lp: WindowManager.LayoutParams, onTap: (() -> Unit)?) {
        var ix = 0
        var iy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = ix + dx.toInt()
                    lp.y = iy + dy.toInt()
                    wm.updateViewLayout(target, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
            }
            true
        }
    }

    private fun expand() {
        val b = bubble ?: return
        val p = panel ?: return
        if (bubbleOn) { wm.removeView(b); bubbleOn = false }
        if (!panelOn) { wm.addView(p, panelLp); panelOn = true }
    }

    private fun minimize() {
        val b = bubble ?: return
        val p = panel ?: return
        if (panelOn) { wm.removeView(p); panelOn = false }
        if (!bubbleOn) { wm.addView(b, bubbleLp); bubbleOn = true }
    }

    private fun removeAll() {
        if (panel == null) return
        if (panelOn) { wm.removeView(panel); panelOn = false }
        if (bubbleOn) { wm.removeView(bubble); bubbleOn = false }
        web?.destroy()
        web = null
        panel = null
        bubble = null
    }

    companion object {
        const val ACTION_STOP = "com.niniss.luaexecutor.STOP"
    }
}
