package org.openbased.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ForwardingTimeline

/**
 * Plays a remuxed or transcoded stream, which the server cannot seek within (no Range support). Seeking reloads
 * the stream from `?start=<seconds>`; positions are reported as offset + position in the current stream, and the
 * duration is the item's real duration, so the standard controls show and seek the whole item.
 */
@OptIn(UnstableApi::class)
class RestartingPlayer(
    private val exo: ExoPlayer,
    private val durationMs: Long?,
    /** The stream starting at the given position (milliseconds). */
    private val itemAt: (Long) -> MediaItem,
) : ForwardingPlayer(exo) {

    var offsetMs = 0L
        private set

    fun load(startMs: Long) {
        offsetMs = startMs.coerceAtLeast(0).let { if (durationMs != null) it.coerceAtMost(durationMs) else it }
        exo.setMediaItem(itemAt(offsetMs))
        exo.prepare()
    }

    override fun seekTo(positionMs: Long) = load(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = load(positionMs)
    override fun seekBack() = load(currentPosition - seekBackIncrement)
    override fun seekForward() = load(currentPosition + seekForwardIncrement)
    override fun seekToDefaultPosition() = load(0)

    override fun getCurrentPosition(): Long = offsetMs + exo.currentPosition
    override fun getContentPosition(): Long = offsetMs + exo.contentPosition
    override fun getBufferedPosition(): Long = offsetMs + exo.bufferedPosition
    override fun getContentBufferedPosition(): Long = offsetMs + exo.contentBufferedPosition
    override fun getDuration(): Long = durationMs ?: C.TIME_UNSET
    override fun getContentDuration(): Long = durationMs ?: C.TIME_UNSET
    override fun isCurrentMediaItemSeekable(): Boolean = durationMs != null

    override fun isCommandAvailable(command: Int): Boolean =
        (durationMs != null && command in SEEK_COMMANDS) || super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands =
        if (durationMs == null) super.getAvailableCommands()
        else super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .build()

    override fun getCurrentTimeline(): Timeline {
        val timeline = super.getCurrentTimeline()
        val duration = durationMs ?: return timeline
        return object : ForwardingTimeline(timeline) {
            override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                window.durationUs = duration * 1000
                window.isSeekable = true
                return window
            }
        }
    }

    private companion object {
        val SEEK_COMMANDS = listOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        )
    }
}
