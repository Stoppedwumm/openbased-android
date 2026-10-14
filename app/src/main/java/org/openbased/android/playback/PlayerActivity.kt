package org.openbased.android.playback

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.openbased.android.OpenBasedApp
import org.openbased.android.R
import org.openbased.android.api.CreateSession
import org.openbased.android.api.Device
import java.io.File

/**
 * Plays a media item: a downloaded file when [EXTRA_OFFLINE] is set, otherwise a playback session from the
 * server, which picks direct play, remux or transcode for this device's codecs. Progress is recorded every few
 * seconds and when playback stops, and synced to the server when online.
 *
 * - Picture-in-picture: leaving the player while it plays shrinks it into a window with rewind, play/pause and
 *   forward buttons.
 * - Dual screens: when the device has a second display (dual-screen consoles, or an external display), the video
 *   plays full screen there and this screen shows the controls. "Swap screens" moves the controls to the other
 *   display, so the video moves here.
 * - Game controllers: A plays/pauses, L1/R1 seek, Select swaps screens, Y shows the controls.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private val container by lazy { (application as OpenBasedApp).container }
    private val settings by lazy { container.settings }
    private lateinit var exo: ExoPlayer
    private lateinit var root: FrameLayout
    private lateinit var view: PlayerView
    private var player: Player? = null
    private var mediaSession: MediaSession? = null
    private var presentation: VideoPresentation? = null
    private var dualScreenDeclined = false
    private var title: String = ""
    private var sessionId: String? = null
    private var durationMs: Long? = null
    private var progressJob: Job? = null
    private val mediaId by lazy { intent.getStringExtra(EXTRA_MEDIA_ID)!! }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateScreens()
        override fun onDisplayRemoved(displayId: Int) = updateScreens()
        override fun onDisplayChanged(displayId: Int) = updateScreens()
    }

    private val pipControls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = player ?: return
            when (intent.getStringExtra(EXTRA_CONTROL)) {
                CONTROL_PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
                CONTROL_BACK -> p.seekBack()
                CONTROL_FORWARD -> p.seekForward()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Streams and side-loaded subtitles go through the authenticated client; local files through the file source.
        val dataSource = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.http))
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        view = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setBackgroundColor(Color.BLACK)
        }
        // The root sees keys before the player view, so controller buttons work whatever has focus.
        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent) = handleKey(event) || super.dispatchKeyEvent(event)
        }.apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        showVideoHere()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    record(completed = true)
                    finish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) record()
                updatePictureInPicture()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) = updatePictureInPicture()

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })
        ContextCompat.registerReceiver(this, pipControls, IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        displays.registerDisplayListener(displayListener, null)
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0)
        lifecycleScope.launch {
            title = container.db.catalog().itemNow(mediaId)?.let { it.seriesTitle?.let { s -> "$s · ${it.title}" } ?: it.title }
                ?: ""
            try {
                if (intent.getBooleanExtra(EXTRA_OFFLINE, false)) playDownload(startMs) else stream(startMs)
                startProgressUpdates()
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Cannot play: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun playDownload(startMs: Long) {
        val download = container.downloads.get(mediaId) ?: error("The download is gone")
        durationMs = download.durationMs
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(File(download.filePath!!)))
            .setSubtitleConfigurations(container.downloads.subtitles(download).map {
                subtitle(Uri.fromFile(File(it.path)), it.language, it.label, it.forced, it.defaultTrack)
            })
            .build()
        exo.setMediaItem(item, startMs)
        exo.prepare()
        attach(exo)
    }

    private suspend fun stream(startMs: Long) {
        val api = container.api
        val session = api.createSession(
            CreateSession(
                mediaId = mediaId,
                device = Device(name = "${Build.MANUFACTURER} ${Build.MODEL}", platform = "Android ${Build.VERSION.RELEASE}"),
                capabilities = DeviceCapabilities.device,
            ),
        )
        sessionId = session.sessionId
        durationMs = container.db.catalog().itemNow(mediaId)?.durationMs
            ?: runCatching { api.mediaDetail(mediaId).duration }.getOrNull()
        val subtitles = runCatching { api.subtitles(mediaId, session.fileId) }.getOrDefault(emptyList())
            .filter { it.text }
            .map {
                subtitle(
                    Uri.parse(api.url("/api/v1/media/$mediaId/subtitles/${it.id}?fileId=${session.fileId}")),
                    it.language,
                    listOfNotNull(it.language?.uppercase(), it.title).joinToString(" · ").ifBlank { "Subtitles" },
                    it.forced,
                    it.defaultTrack,
                )
            }
        val streamUrl = api.url(session.streamUrl)
        if (session.mode == "DIRECT_PLAY") {
            exo.setMediaItem(MediaItem.Builder().setUri(streamUrl).setSubtitleConfigurations(subtitles).build(), startMs)
            exo.prepare()
            attach(exo)
        } else {
            // Remuxed and transcoded streams cannot be seeked within; restart them at the position instead.
            val restarting = RestartingPlayer(exo, durationMs) { at ->
                val url = streamUrl.toHttpUrl().newBuilder()
                    .apply { if (at > 0) addQueryParameter("start", "%.3f".format(java.util.Locale.ROOT, at / 1000.0)) }
                    .build().toString()
                MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitles).build()
            }
            restarting.load(startMs)
            attach(restarting)
        }
    }

    private fun attach(player: Player) {
        this.player = player
        mediaSession = MediaSession.Builder(this, player).setId("player-$mediaId-${System.nanoTime()}").build()
        player.playWhenReady = true
        updateScreens()
        updatePictureInPicture()
    }

    // --- Screens

    /** The display for the video, if the device has another one and dual-screen mode is on. */
    private fun otherDisplay(): Display? {
        if (!settings.dualScreen || dualScreenDeclined || isInPictureInPictureMode) return null
        val current = currentDisplay() ?: return null
        val presentationIds = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { it.displayId }.toSet()
        val screens = displays.displays.map {
            PlayerModes.Screen(it.displayId, it.displayId in presentationIds, it.state != Display.STATE_OFF)
        }
        return PlayerModes.otherScreen(screens, current.displayId)?.let { displays.getDisplay(it) }
    }

    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
        else @Suppress("DEPRECATION") windowManager.defaultDisplay

    private fun updateScreens() {
        val p = player ?: return
        val other = otherDisplay()
        when {
            other == null && presentation != null -> showVideoHere()
            other != null && presentation?.display?.displayId != other.displayId -> showVideoOn(other, p)
        }
        if (presentation == null) view.player = p
    }

    /** Video on the other display, controls here. */
    private fun showVideoOn(display: Display, p: Player) {
        presentation?.let { it.detach(); it.dismiss() }
        view.player = null
        val shown = VideoPresentation(this, display, p)
        try {
            shown.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            showVideoHere()
            return
        }
        presentation = shown
        root.removeAllViews()
        root.addView(controlPanel(p), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        // The controls screen may be small or portrait; let it rotate freely.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updatePictureInPicture()
    }

    /** Video and controls on this display. */
    private fun showVideoHere() {
        presentation?.let { it.detach(); it.dismiss() }
        presentation = null
        root.removeAllViews()
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        view.player = player
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        updatePictureInPicture()
    }

    /** Touch controls for the screen without the video: title, always-visible transport controls, screen buttons. */
    private fun controlPanel(p: Player) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(18, 18, 22))
        setPadding(32, 32, 32, 32)
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            setText(R.string.playing_on_other_screen)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        })
        addView(
            PlayerControlView(context).apply {
                player = p
                showTimeoutMs = 0
                setShowSubtitleButton(true)
                show()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(Button(context).apply {
                setText(R.string.swap_screens)
                setOnClickListener { swapScreens() }
            })
            addView(Button(context).apply {
                setText(R.string.use_one_screen)
                setOnClickListener {
                    dualScreenDeclined = true
                    showVideoHere()
                }
            })
        })
    }

    /**
     * Moves the controls to the other display by restarting the player there; the video then plays on this one.
     * Some devices do not let apps start on their second screen; they keep the current layout.
     */
    private fun swapScreens() {
        val target = presentation?.display ?: otherDisplay() ?: return
        val p = player ?: return
        record()
        val intent = startIntent(this, mediaId, intent.getBooleanExtra(EXTRA_OFFLINE, false), p.currentPosition)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId).toBundle())
            finish()
        } catch (e: SecurityException) {
            Toast.makeText(this, "This device does not let apps open on the other screen. Use its screen-swap " +
                "button instead.", Toast.LENGTH_LONG).show()
        }
    }

    // --- Picture-in-picture

    private fun pictureInPictureAllowed() = settings.pictureInPicture && presentation == null &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pictureInPictureParams(): PictureInPictureParams {
        val p = player
        val size = p?.videoSize
        val (num, den) = PlayerModes.pipAspect(size?.width ?: 0, size?.height ?: 0)
        val playing = p?.isPlaying == true
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(num, den))
            .setActions(listOf(
                pipAction(android.R.drawable.ic_media_rew, "Back 10 seconds", CONTROL_BACK, 1),
                if (playing) pipAction(android.R.drawable.ic_media_pause, "Pause", CONTROL_PLAY_PAUSE, 2)
                else pipAction(android.R.drawable.ic_media_play, "Play", CONTROL_PLAY_PAUSE, 2),
                pipAction(android.R.drawable.ic_media_ff, "Forward 30 seconds", CONTROL_FORWARD, 3),
            ))
        // Where the video is on screen, so the window animates from it.
        val bounds = Rect()
        if (view.videoSurfaceView?.getGlobalVisibleRect(bounds) == true && !bounds.isEmpty) {
            builder.setSourceRectHint(bounds)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ enters picture-in-picture by itself when the user leaves while it plays.
            builder.setAutoEnterEnabled(playing && pictureInPictureAllowed()).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipAction(icon: Int, label: String, control: String, requestCode: Int) = RemoteAction(
        Icon.createWithResource(this, icon), label, label,
        PendingIntent.getBroadcast(this, requestCode,
            Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_CONTROL, control),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    )

    private fun updatePictureInPicture() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pictureInPictureAllowed() && player?.isPlaying == true) {
            runCatching { enterPictureInPictureMode(pictureInPictureParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        view.useController = !isInPictureInPictureMode
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            // The picture-in-picture window was closed.
            finish()
        }
    }

    // --- Game controllers

    /** Returns true when the key was a player shortcut. */
    private fun handleKey(event: KeyEvent): Boolean {
        val p = player ?: return false
        val controlsVisible = presentation != null || view.isControllerFullyVisible
        val action = PlayerModes.keyAction(event.keyCode, controlsVisible) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (action) {
            PlayerModes.KeyAction.PLAY_PAUSE -> if (p.isPlaying) p.pause() else p.play()
            PlayerModes.KeyAction.SEEK_BACK -> p.seekBack()
            PlayerModes.KeyAction.SEEK_FORWARD -> p.seekForward()
            PlayerModes.KeyAction.SWAP_SCREENS -> swapScreens()
            PlayerModes.KeyAction.TOGGLE_CONTROLS ->
                if (presentation == null) {
                    if (controlsVisible) view.hideController() else view.showController()
                }
        }
        return true
    }

    private fun subtitle(uri: Uri, language: String?, label: String, forced: Boolean, default: Boolean) =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(
                (if (forced) C.SELECTION_FLAG_FORCED else 0) or (if (default) C.SELECTION_FLAG_DEFAULT else 0),
            )
            .build()

    private fun startProgressUpdates() {
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                if (player?.isPlaying == true) record()
            }
        }
    }

    /** Queues the current position; the queue is sent to the server when online. */
    private fun record(completed: Boolean? = null) {
        val p = player ?: return
        val position = p.currentPosition.coerceAtLeast(0) / 1000.0
        if (position < 1 && completed != true) return
        val duration = (durationMs ?: p.duration.takeIf { it != C.TIME_UNSET })?.div(1000.0)
        // The app's scope, so it is not cancelled when the activity closes.
        container.appScope.launch {
            container.progress.record(mediaId, if (completed == true) duration ?: position else position, duration,
                completed)
        }
    }

    override fun onStop() {
        super.onStop()
        // Also with the video on the other screen: the presentation belongs to this activity and may be hidden with it.
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(pipControls)
        presentation?.let { it.detach(); it.dismiss() }
        mediaSession?.release()
        exo.release()
        sessionId?.let { id -> container.appScope.launch { runCatching { container.api.endSession(id) } } }
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "mediaId"
        private const val EXTRA_OFFLINE = "offline"
        private const val EXTRA_START_MS = "startMs"
        private const val EXTRA_CONTROL = "control"
        private const val ACTION_PIP_CONTROL = "org.openbased.android.PIP_CONTROL"
        private const val CONTROL_PLAY_PAUSE = "playPause"
        private const val CONTROL_BACK = "back"
        private const val CONTROL_FORWARD = "forward"
        private const val PROGRESS_INTERVAL_MS = 10_000L

        private fun startIntent(context: Context, mediaId: String, offline: Boolean, startMs: Long) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_MEDIA_ID, mediaId)
                .putExtra(EXTRA_OFFLINE, offline)
                .putExtra(EXTRA_START_MS, startMs)

        fun start(context: Context, mediaId: String, offline: Boolean, startMs: Long) {
            context.startActivity(startIntent(context, mediaId, offline, startMs).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
