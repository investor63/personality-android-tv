package com.personality.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        var active: RemoteAccessibilityService? = null
            private set
        var stateListener: (() -> Unit)? = null
        var listener: ((KeyEvent) -> Unit)? = null
        val protectedKeys = setOf(
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        )
        fun signature(event: KeyEvent): String {
            val device = event.device
            return "${device?.vendorId}:${device?.productId}:${device?.descriptor}:${event.keyCode}:${event.scanCode}"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private data class Press(val signature: String, val single: String?, val double: String?, val long: String?, val previous: Pending?)
    private data class Pending(val up: Long, val task: Runnable)
    private val consumed = mutableMapOf<Pair<Int, Int>, Press?>()
    private val pending = mutableMapOf<String, Pending>()
    private var overlay: View? = null
    val isBlack get() = overlay != null

    override fun onServiceConnected() { active = this; stateListener?.invoke() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (isBlack && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER)) {
            restoreScreen()
        }
        val key = event.deviceId to event.keyCode
        if (isBlack && event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            consumed[key] = null
            restoreScreen()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (!consumed.containsKey(key)) return false
            val press = consumed.remove(key)
            if (press != null) {
                if (event.isCanceled) press.previous?.task?.let(handler::post) else finishPress(press, event)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return consumed.containsKey(key)
        val capture = listener
        if (event.keyCode in protectedKeys) {
            capture?.invoke(event)
            return false
        }
        if (capture != null) {
            recordKey(event)
            consumed[key] = null
            capture(event)
            return true
        }
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return false
        val id = signature(event)
        val press = Press(id, prefs.getString("button:$id", null),
            prefs.getString("button:$id:double", null), prefs.getString("button:$id:long", null),
            pending.remove(id)?.also { handler.removeCallbacks(it.task) })
        if (press.single == null && press.double == null && press.long == null) return false
        recordKey(event)
        consumed[key] = press
        return true
    }

    private fun recordKey(event: KeyEvent) {
        DeviceDiagnosticsManager.record(this, "keyCode=${event.keyCode} scanCode=${event.scanCode} deviceId=${event.deviceId}")
    }

    private fun finishPress(press: Press, event: KeyEvent) {
        val previous = press.previous
        val long = press.long != null && ButtonEventProcessor.isLong(event.downTime, event.eventTime)
        if (long) {
            previous?.task?.run()
            press.long?.let { handler.post { ActionRegistry.run(this, it) } }
            return
        }
        if (press.double != null && previous != null &&
            ButtonEventProcessor.isDouble(previous.up, event.downTime)) {
            handler.post { ActionRegistry.run(this, press.double) }
            return
        }
        previous?.task?.run()
        if (press.double == null) {
            press.single?.let { handler.post { ActionRegistry.run(this, it) } }
            return
        }
        val task = Runnable {
            pending.remove(press.signature)
            press.single?.let { ActionRegistry.run(this, it) }
        }
        pending[press.signature] = Pending(event.eventTime, task)
        handler.postDelayed(task, ButtonEventProcessor.DOUBLE_PRESS_MS + 1)
    }

    fun blackScreen(): Boolean {
        if (isBlack) return true
        val black = object : View(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) restoreScreen()
                    return true
                }
                if (event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            @Suppress("DEPRECATION")
            systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        return runCatching {
            windowManager.addView(black, params)
            overlay = black
            black.requestFocus()
        }.isSuccess
    }

    fun restoreScreen() {
        overlay?.let { runCatching { windowManager.removeViewImmediate(it) } }
        overlay = null
        stateListener?.invoke()
    }

    override fun onInterrupt() { restoreScreen() }
    override fun onDestroy() {
        restoreScreen()
        handler.removeCallbacksAndMessages(null)
        active = null
        stateListener?.invoke()
        super.onDestroy()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
}
