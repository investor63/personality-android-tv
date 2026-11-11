package com.personality.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

object ActionRegistry {
    private fun media(context: Context, code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun volume(context: Context, direction: Int) {
        context.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun section(context: Context, index: Int) {
        context.startActivity(Intent(context, MainActivity::class.java).putExtra("section", index)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    private fun changeSaver(context: Context, offset: Int, random: Boolean = false) {
        val videos = ScreensaverPlayer.library(context)
        if (videos.isEmpty()) {
            Toast.makeText(context, R.string.empty, Toast.LENGTH_LONG).show()
            return
        }
        val last = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("last_video", null)
        val index = Math.floorMod(videos.indexOf(last).coerceAtLeast(0) + offset, videos.size)
        val uri = if (random) videos.random() else videos[index]
        ScreensaverPlayer.launch(context, uri)
    }

    data class Action(val title: Int, val description: Int, val execute: (Context) -> Unit)

    val actions = linkedMapOf(
        "toggle" to Action(R.string.toggle, R.string.desc_toggle) {
            val service = RemoteAccessibilityService.active
            if (service?.isBlack == true) service.restoreScreen()
            else if (service?.blackScreen() != true) Toast.makeText(it, R.string.overlay_failed, Toast.LENGTH_LONG).show()
        },
        "play_pause" to Action(R.string.play_pause, R.string.desc_play_pause) { media(it, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) },
        "next_track" to Action(R.string.next_track, R.string.desc_next_track) { media(it, KeyEvent.KEYCODE_MEDIA_NEXT) },
        "prev_track" to Action(R.string.prev_track, R.string.desc_prev_track) { media(it, KeyEvent.KEYCODE_MEDIA_PREVIOUS) },
        "volume_up" to Action(R.string.volume_up, R.string.desc_volume_up) { volume(it, AudioManager.ADJUST_RAISE) },
        "volume_down" to Action(R.string.volume_down, R.string.desc_volume_down) { volume(it, AudioManager.ADJUST_LOWER) },
        "mute" to Action(R.string.mute, R.string.desc_mute) { volume(it, AudioManager.ADJUST_TOGGLE_MUTE) },
        "home" to Action(R.string.home, R.string.desc_home) {
            RemoteAccessibilityService.active?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        },
        "back" to Action(R.string.back, R.string.desc_back) {
            RemoteAccessibilityService.active?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        },
        "settings" to Action(R.string.system_settings, R.string.desc_system_settings) {
            it.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        "screensaver" to Action(R.string.play_saver, R.string.desc_play_saver) { context ->
            val player = ScreensaverPlayer.active
            if (player != null && !player.isFinishing) player.finish() else ScreensaverPlayer.launch(context)
        },
        "stop_saver" to Action(R.string.stop_saver, R.string.desc_stop_saver) { ScreensaverPlayer.active?.finish() },
        "choose_saver" to Action(R.string.choose_saver, R.string.desc_choose_saver) { section(it, 3) },
        "next_saver" to Action(R.string.next_saver, R.string.desc_next_saver) { changeSaver(it, 1) },
        "previous_saver" to Action(R.string.previous_saver, R.string.desc_previous_saver) { changeSaver(it, -1) },
        "random_saver" to Action(R.string.random_saver, R.string.desc_random_saver) { changeSaver(it, 0, true) },
        "start_timer" to Action(R.string.start_timer, R.string.desc_start_timer) {
            val prefs = it.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val minutes = prefs.getInt("timer_minutes", 30)
            val action = prefs.getString("last_timer_action", "stop_saver") ?: "stop_saver"
            SleepTimer.start(it, minutes, action)
            Toast.makeText(it, R.string.timer_set, Toast.LENGTH_LONG).show()
        },
        "launch_app" to Action(R.string.launch_app, R.string.desc_launch_app) {
            val packageName = it.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("launch_package", null)
            val intent = packageName?.let(it.packageManager::getLeanbackLaunchIntentForPackage)
            if (intent != null) it.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else section(it, 6)
        },
        "cancel_timer" to Action(R.string.cancel_timer, R.string.desc_cancel_timer) { SleepTimer.cancel(it) },
        "none" to Action(R.string.none, R.string.desc_none) {}
    )

    fun resolve(id: String): Action? = actions[when (id) {
        "black", "restore" -> "toggle"
        else -> id
    }]

    fun run(context: Context, id: String) {
        DeviceDiagnosticsManager.record(context, "action=$id")
        runCatching { resolve(id)?.execute?.invoke(context) }.onFailure {
            DeviceDiagnosticsManager.record(context, "error=${it.javaClass.simpleName}")
            Toast.makeText(context, R.string.action_failed, Toast.LENGTH_LONG).show()
        }
    }
}
