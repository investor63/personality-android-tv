package com.personality.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ScreensaverPlayer : AppCompatActivity() {
    companion object {
        var active: ScreensaverPlayer? = null
            private set
        fun launch(context: Context, uri: String? = null) {
            context.startActivity(Intent(context, ScreensaverPlayer::class.java)
                .putExtra("uri", uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun library(context: Context): List<String> =
            context.getSharedPreferences("videos", Context.MODE_PRIVATE).all.keys.sorted()
    }

    private var engine: ExoPlayer? = null
    private lateinit var view: PlayerView
    private var current: String? = null
    private var position = 0L
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        view = PlayerView(this)
        setContentView(view)
        current = savedInstanceState?.getString("uri") ?: intent.getStringExtra("uri")
        position = savedInstanceState?.getLong("position") ?: 0
        muted = savedInstanceState?.getBoolean("muted") ?: false
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("uri", current)
        state.putLong("position", engine?.currentPosition ?: position)
        state.putBoolean("muted", muted)
        super.onSaveInstanceState(state)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("uri")?.let { play(it) }
    }

    override fun onStart() {
        super.onStart()
        val library = library(this)
        val last = getSharedPreferences("settings", MODE_PRIVATE).getString("last_video", null)
        val uri = current ?: last?.takeIf(library::contains) ?: library.firstOrNull()
        if (uri == null) {
            Toast.makeText(this, R.string.empty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        active = this
        engine = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            volume = if (muted) 0f else 1f
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@ScreensaverPlayer, R.string.video_error, Toast.LENGTH_LONG).show()
                    finish()
                }
            })
        }
        view.player = engine
        play(uri, position)
        view.hideController()
        view.requestFocus()
    }

    private fun play(uri: String, start: Long = 0) {
        current = uri
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_video", uri).apply()
        engine?.apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)), start)
            prepare()
            playWhenReady = true
        }
    }

    private fun changeVideo(offset: Int, random: Boolean = false) {
        val videos = library(this)
        if (videos.isEmpty()) return
        val index = Math.floorMod(videos.indexOf(current).coerceAtLeast(0) + offset, videos.size)
        play(if (random) videos.random() else videos[index])
    }

    private fun toggleMute() {
        muted = !muted
        engine?.volume = if (muted) 0f else 1f
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val visible = view.isControllerFullyVisible
        val direction = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> 2
            KeyEvent.KEYCODE_DPAD_DOWN -> 3
            else -> -1
        }
        if (!visible && direction >= 0) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val default = if (direction < 2) direction else 4
                when (getSharedPreferences("settings", MODE_PRIVATE)
                    .getInt("player_direction:$direction", default)) {
                    0 -> changeVideo(-1)
                    1 -> changeVideo(1)
                    2 -> changeVideo(0, random = true)
                    3 -> toggleMute()
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (visible) view.hideController() else view.showController()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) toggleMute()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        position = engine?.currentPosition ?: 0
        view.player = null
        engine?.release()
        engine = null
        if (active === this) active = null
        super.onStop()
    }
}


