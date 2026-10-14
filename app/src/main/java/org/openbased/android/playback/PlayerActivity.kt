package org.openbased.android.playback

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.openbased.android.OpenBasedApp
import org.openbased.android.api.CreateSession
import org.openbased.android.api.Device
import java.io.File

/**
 * Plays a media item: a downloaded file when [EXTRA_OFFLINE] is set, otherwise a playback session from the
 * server, which picks direct play, remux or transcode for this device's codecs. Progress is recorded every few
 * seconds and when playback stops, and synced to the server when online.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private val container by lazy { (application as OpenBasedApp).container }
    private lateinit var exo: ExoPlayer
    private lateinit var view: PlayerView
    private var player: Player? = null
    private var sessionId: String? = null
    private var durationMs: Long? = null
    private var progressJob: Job? = null
    private val mediaId by lazy { intent.getStringExtra(EXTRA_MEDIA_ID)!! }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Streams and side-loaded subtitles go through the authenticated client; local files through the file source.
        val dataSource = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(container.http))
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        view = PlayerView(this).apply {
            keepScreenOn = true
            setShowSubtitleButton(true)
        }
        setContentView(view)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    record(completed = true)
                    finish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) record()
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0)
        lifecycleScope.launch {
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
        view.player = player
        player.playWhenReady = true
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
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        exo.release()
        sessionId?.let { id -> container.appScope.launch { runCatching { container.api.endSession(id) } } }
    }

    companion object {
        private const val EXTRA_MEDIA_ID = "mediaId"
        private const val EXTRA_OFFLINE = "offline"
        private const val EXTRA_START_MS = "startMs"
        private const val PROGRESS_INTERVAL_MS = 10_000L

        fun start(context: Context, mediaId: String, offline: Boolean, startMs: Long) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_MEDIA_ID, mediaId)
                    .putExtra(EXTRA_OFFLINE, offline)
                    .putExtra(EXTRA_START_MS, startMs),
            )
        }
    }
}
