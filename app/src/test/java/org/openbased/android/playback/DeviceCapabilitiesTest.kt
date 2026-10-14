package org.openbased.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {

    @Test
    fun decoderTypesMapToServerNames() {
        val caps = DeviceCapabilities.fromDecoderMimeTypes(
            setOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "audio/mp4a-latm", "audio/eac3", "VIDEO/AV01"),
        )
        assertEquals(setOf("h264", "hevc", "vp9", "av1"), caps.videoCodecs)
        assertTrue("eac3" in caps.audioCodecs)
        assertTrue("ac3" !in caps.audioCodecs)
        // ExoPlayer decodes these in software on every device.
        assertTrue(caps.audioCodecs.containsAll(setOf("aac", "opus", "flac", "vorbis")))
        assertTrue(caps.containers.containsAll(setOf("mp4", "mkv", "webm")))
    }
}
