package org.openbased.android.playback

import android.media.MediaCodecList
import org.openbased.android.api.Capabilities

/** What this device can play, in the server's codec and container names. */
object DeviceCapabilities {

    /** Containers ExoPlayer can read. The server treats mov, m4v and m4a as mp4. */
    val CONTAINERS = setOf("mp4", "mkv", "webm", "ts", "avi", "mp3", "flac", "ogg", "wav")

    private val VIDEO = mapOf(
        "video/avc" to "h264",
        "video/hevc" to "hevc",
        "video/av01" to "av1",
        "video/x-vnd.on2.vp9" to "vp9",
        "video/x-vnd.on2.vp8" to "vp8",
        "video/mp4v-es" to "mpeg4",
    )

    private val AUDIO = mapOf(
        "audio/mp4a-latm" to "aac",
        "audio/mpeg" to "mp3",
        "audio/opus" to "opus",
        "audio/vorbis" to "vorbis",
        "audio/flac" to "flac",
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/alac" to "alac",
    )

    /** Maps decoder MIME types to the server's names. ExoPlayer decodes FLAC, Opus and Vorbis itself too. */
    fun fromDecoderMimeTypes(mimeTypes: Set<String>): Capabilities {
        val types = mimeTypes.map { it.lowercase() }.toSet()
        return Capabilities(
            containers = CONTAINERS,
            videoCodecs = VIDEO.filterKeys { it in types }.values.toSet(),
            audioCodecs = AUDIO.filterKeys { it in types }.values.toSet() + setOf("aac", "mp3", "flac", "opus", "vorbis"),
        )
    }

    /** Reads the device's decoders once. */
    val device: Capabilities by lazy {
        val mimes = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .toSet()
        fromDecoderMimeTypes(mimes)
    }
}
