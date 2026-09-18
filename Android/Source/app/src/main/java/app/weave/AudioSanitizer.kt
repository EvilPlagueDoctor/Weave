package app.weave

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

const val MAX_AUDIO_DURATION_MS = 10L * 60L * 1000L
const val MAX_SANITIZED_AUDIO_BYTES = 16 * 1024 * 1024
private const val AUDIO_MIME = "audio/mp4a-latm"
private const val CODEC_TIMEOUT_US = 10_000L

data class SanitizedAudio(
    val bytes: ByteArray,
    val durationMs: Long,
)

/**
 * Decode -> PCM -> fresh AAC/M4A.
 *
 * Only audio samples survive this boundary. Source ID3/Vorbis/MP4 metadata, filenames,
 * embedded cover art and other container fields are not copied into the generated M4A.
 */
fun sanitizeAudioToM4a(context: Context, uri: Uri): SanitizedAudio? {
    val extractor = MediaExtractor()
    val temp = File.createTempFile("weave-audio-", ".m4a", context.cacheDir)
    var decoderForCleanup: MediaCodec? = null
    var encoderForCleanup: MediaCodec? = null
    var muxerForCleanup: MediaMuxer? = null
    var muxerStarted = false

    try {
        extractor.setDataSource(context, uri, null)
        var audioTrack = -1
        var sourceFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrack = i
                sourceFormat = candidate
                break
            }
        }
        val inputFormat = sourceFormat ?: return null
        if (audioTrack < 0) return null

        val durationUs = inputFormat.longOrZero(MediaFormat.KEY_DURATION)
        if (durationUs > MAX_AUDIO_DURATION_MS * 1000L) return null
        val sourceMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
        val sampleRate = inputFormat.integerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            .coerceIn(8_000, 96_000)
        val channels = inputFormat.integerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            .coerceIn(1, 2)

        extractor.selectTrack(audioTrack)

        val decoder = MediaCodec.createDecoderByType(sourceMime)
        decoderForCleanup = decoder
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val encoderFormat = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 96_000 else 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        encoderForCleanup = encoder
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerForCleanup = muxer

        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        var sourceEosQueued = false
        var decoderEosSeen = false
        var encoderEosQueued = false
        var encoderEosSeen = false
        var muxTrack = -1

        var pendingPcm: ByteArray? = null
        var pendingOffset = 0
        var pendingPtsUs = 0L
        var lastQueuedPtsUs = 0L

        fun queuePendingPcm(): Boolean {
            val pcm = pendingPcm ?: return false
            val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIndex < 0) return false
            val input = encoder.getInputBuffer(inputIndex) ?: return false
            input.clear()
            val remaining = pcm.size - pendingOffset
            val amount = minOf(remaining, input.remaining())
            if (amount <= 0) return false
            input.put(pcm, pendingOffset, amount)

            val bytesPerFrame = max(1, channels * 2) // Android decoder PCM is 16-bit by default.
            val framesBefore = pendingOffset / bytesPerFrame
            val ptsUs = pendingPtsUs + (framesBefore * 1_000_000L / sampleRate)
            encoder.queueInputBuffer(inputIndex, 0, amount, ptsUs, 0)
            lastQueuedPtsUs = maxOf(lastQueuedPtsUs, ptsUs)

            pendingOffset += amount
            if (pendingOffset >= pcm.size) {
                pendingPcm = null
                pendingOffset = 0
            }
            return true
        }

        while (!encoderEosSeen) {
            if (!sourceEosQueued) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex) ?: return null
                    input.clear()
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sourceEosQueued = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            size,
                            extractor.sampleTime.coerceAtLeast(0L),
                            0,
                        )
                        extractor.advance()
                    }
                }
            }

            if (pendingPcm == null && !decoderEosSeen) {
                when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (decoderInfo.size > 0 && output != null) {
                            output.position(decoderInfo.offset)
                            output.limit(decoderInfo.offset + decoderInfo.size)
                            pendingPcm = ByteArray(decoderInfo.size).also { output.get(it) }
                            pendingPtsUs = decoderInfo.presentationTimeUs
                            pendingOffset = 0
                        }
                        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderEosSeen = true
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            while (pendingPcm != null && queuePendingPcm()) Unit

            if (decoderEosSeen && pendingPcm == null && !encoderEosQueued) {
                val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        lastQueuedPtsUs + 1L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    encoderEosQueued = true
                }
            }

            var keepDraining = true
            while (keepDraining) {
                when (val outputIndex = encoder.dequeueOutputBuffer(encoderInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) return null
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> keepDraining = false
                    else -> if (outputIndex >= 0) {
                        val output = encoder.getOutputBuffer(outputIndex)
                        if (output != null &&
                            encoderInfo.size > 0 &&
                            encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            if (!muxerStarted || muxTrack < 0) return null
                            output.position(encoderInfo.offset)
                            output.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(muxTrack, output, encoderInfo)
                        }
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderEosSeen = true
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        if (muxerStarted) {
            muxer.stop()
            muxerStarted = false
        }

        val bytes = temp.readBytes()
        if (bytes.isEmpty() || bytes.size > MAX_SANITIZED_AUDIO_BYTES) return null
        return SanitizedAudio(
            bytes = bytes,
            durationMs = if (durationUs > 0L) durationUs / 1000L else 0L,
        )
    } catch (_: Throwable) {
        return null
    } finally {
        runCatching { extractor.release() }
        runCatching { decoderForCleanup?.stop() }
        runCatching { decoderForCleanup?.release() }
        runCatching { encoderForCleanup?.stop() }
        runCatching { encoderForCleanup?.release() }
        if (muxerStarted) runCatching { muxerForCleanup?.stop() }
        runCatching { muxerForCleanup?.release() }
        runCatching { temp.delete() }
    }
}

private fun MediaFormat.integerOr(key: String, fallback: Int): Int =
    runCatching { getInteger(key) }.getOrDefault(fallback)

private fun MediaFormat.longOrZero(key: String): Long =
    runCatching { getLong(key) }.getOrDefault(0L)
