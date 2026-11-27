package io.github.trevarj.motd.attachment

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val OUTPUT_DIRECTORY = "upload-video-compression"
private const val MP4 = "video/mp4"
private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
private const val MIN_VIDEO_BITRATE = 128_000
private const val AUDIO_BITRATE = 96_000

internal class CompressedUploadVideo(
    val source: AttachmentSource.LocalFile,
) : Closeable {
    override fun close() {
        source.file.delete()
    }
}

internal data class UploadVideoBitrates(
    val video: Int,
    val audio: Int,
)

internal fun uploadVideoBitrates(
    limitBytes: Long,
    durationUs: Long,
    hasAudio: Boolean,
): UploadVideoBitrates? {
    if (limitBytes <= 0 || durationUs <= 0) return null
    // Reserve space for the MP4 tables and for encoders that overshoot their requested rate.
    val payloadBytes = limitBytes - maxOf(8 * 1024L, limitBytes / 6)
    val audio = if (hasAudio) AUDIO_BITRATE else 0
    // Use Double before multiplying so caller-supplied Long values cannot overflow.
    val video = (payloadBytes.toDouble() * 8_000_000 / durationUs - audio).toInt()
    return if (video >= MIN_VIDEO_BITRATE) UploadVideoBitrates(video, audio) else null
}

internal fun UploadVideoBitrates.calibrateForOversize(
    actualBytes: Long,
    limitBytes: Long,
): UploadVideoBitrates? {
    if (limitBytes <= 0 || actualBytes <= limitBytes) return null
    // Scale the total request from the measured result, leaving 10% room for the second pass.
    val scale = 0.9 * limitBytes.toDouble() / actualBytes
    val lowerVideo = ((video.toLong() + audio) * scale - audio).toInt()
    return if (lowerVideo in MIN_VIDEO_BITRATE until video) UploadVideoBitrates(lowerVideo, audio) else null
}

internal fun Uri.isVideoCompressionInputUri(): Boolean =
    isLocalMediaUri() && isHierarchical && fragment == null &&
        when (scheme) {
            ContentResolver.SCHEME_FILE -> authority.isNullOrEmpty() && query == null && path?.startsWith('/') == true
            else -> !authority.isNullOrBlank()
        }

/** Encodes a separate private copy; the caller owns the returned file until [CompressedUploadVideo.close]. */
@OptIn(UnstableApi::class)
internal suspend fun compressUploadVideo(
    context: Context,
    source: AttachmentSource,
    limitBytes: Long,
): CompressedUploadVideo {
    val (input, name) =
        when (source) {
            is AttachmentSource.Document -> source.uri to source.name
            is AttachmentSource.LocalFile -> source.file.toUri() to source.name
            else -> throw IOException("Video compression only accepts a local file")
        }
    if (!input.isVideoCompressionInputUri()) throw IOException("Invalid local video URI")

    // Acquire inside the IO block, but retain ownership outside it: cancellation can drop its return value.
    var pendingFile: File? = null
    var stagedFile: File? = null
    try {
        val compressed =
            withContext(Dispatchers.IO) {
                val (encodeUri, media) =
                    openVideoDescriptor(context, input).use { descriptor ->
                        if (input.scheme == ContentResolver.SCHEME_CONTENT && !descriptor.isSeekable()) {
                            val staged =
                                File
                                    .createTempFile("input-", ".media", compressionCache(context))
                                    .also { stagedFile = it }
                            stageVideo(descriptor, staged)
                            currentCoroutineContext().ensureActive()
                            val stagedUri = staged.toUri()
                            stagedUri to openVideoDescriptor(context, stagedUri).use { inspectVideo(it, checkDecoder = true) }
                        } else {
                            input to inspectVideo(descriptor, checkDecoder = true)
                        }
                    }
                val bitrates =
                    uploadVideoBitrates(limitBytes, media.durationUs, media.audioMime != null)
                        ?: throw IOException("The selected limit is too low for this video")
                if (EncoderSelector.DEFAULT.selectEncoderInfos(AVC).isEmpty() ||
                    (bitrates.audio > 0 && EncoderUtil.getSupportedEncoders(AAC).isEmpty())
                ) {
                    throw IOException("The device has no compatible video or audio encoder")
                }

                val directory = compressionCache(context)

                suspend fun encode(rates: UploadVideoBitrates): Pair<File, Long> {
                    currentCoroutineContext().ensureActive()
                    val output = File.createTempFile("compressed-", ".mp4", directory).also { pendingFile = it }
                    val export = exportVideo(context, encodeUri, output, rates)
                    val actualBytes = output.length()
                    if (actualBytes <= 0L || export.videoFrameCount <= 0 || export.videoEncoderName == null ||
                        (rates.audio > 0 && export.audioEncoderName == null)
                    ) {
                        throw IOException("The export did not encode all required tracks")
                    }
                    val encoded = openVideoDescriptor(context, output.toUri()).use { inspectVideo(it, checkDecoder = false) }
                    val expectedAudio = if (rates.audio > 0) AAC else null
                    if (encoded.videoMime != AVC || encoded.audioMime != expectedAudio) {
                        throw IOException("The compressed video has incompatible tracks")
                    }
                    return output to actualBytes
                }

                val first = encode(bitrates)
                val (output, actualBytes) =
                    if (first.second <= limitBytes) {
                        first
                    } else {
                        val retry =
                            bitrates.calibrateForOversize(first.second, limitBytes)
                                ?: throw IOException("The compressed video exceeds the selected limit")
                        if (!first.first.delete()) throw IOException("Could not remove the oversized compressed video")
                        pendingFile = null
                        encode(retry)
                    }
                if (actualBytes > limitBytes) throw IOException("The compressed video exceeds the selected limit")
                val stem = name.substringBeforeLast('.').ifBlank { "video" }
                CompressedUploadVideo(AttachmentSource.LocalFile(output, "$stem.mp4", MP4, actualBytes))
            }
        currentCoroutineContext().ensureActive()
        pendingFile = null
        return compressed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw IOException("Could not compress the video", error)
    } finally {
        stagedFile?.delete()
        pendingFile?.delete()
    }
}

private data class ProbedVideo(
    val durationUs: Long,
    val videoMime: String,
    val audioMime: String?,
)

private fun compressionCache(context: Context): File {
    val directory = File(context.cacheDir, OUTPUT_DIRECTORY)
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Could not create the compression cache")
    }
    return directory
}

private suspend fun openVideoDescriptor(
    context: Context,
    uri: Uri,
): AssetFileDescriptor {
    val resolver = context.contentResolver
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
        return resolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Cannot open the local video")
    }
    return suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val signal = CancellationSignal()
        continuation.invokeOnCancellation {
            // A provider's cancellation listener can run synchronously; keep it off the cancelling UI thread.
            Dispatchers.IO.dispatch(EmptyCoroutineContext) { runCatching { signal.cancel() } }
        }
        val descriptor =
            try {
                val opened =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val options = Bundle().apply { putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true) }
                        resolver.openTypedAssetFileDescriptor(uri, "*/*", options, signal)
                    } else {
                        resolver.openAssetFileDescriptor(uri, "r", signal)
                    }
                opened ?: throw IOException("Cannot open the local video")
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
                return@suspendCancellableCoroutine
            }
        continuation.resume(descriptor) { _, unused, _ -> runCatching { unused.close() } }
    }
}

private fun AssetFileDescriptor.isSeekable(): Boolean =
    try {
        Os.lseek(fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ESPIPE) throw error
        false
    }

private suspend fun stageVideo(
    descriptor: AssetFileDescriptor,
    file: File,
) {
    // Bound unknown-length input by available cache space, not the destination's upload cap.
    val availableBytes = file.usableSpace
    var remainingBytes = availableBytes - minOf(availableBytes / 20, 8 * 1024 * 1024L)
    val poll =
        arrayOf(
            StructPollfd().apply {
                fd = descriptor.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            },
        )
    val buffer = ByteArray(64 * 1024)
    val coroutine = currentCoroutineContext()
    var offsetBytesLeft = descriptor.startOffset
    var declaredBytesLeft = descriptor.declaredLength
    file.outputStream().use { output ->
        while (declaredBytesLeft != 0L) {
            coroutine.ensureActive()
            // Once readable, a single-reader pipe read will not block. A slow writer cannot stall cancellation.
            if (Os.poll(poll, 100) == 0) continue
            val remaining = if (offsetBytesLeft > 0) offsetBytesLeft else declaredBytesLeft
            val maxRead = if (remaining < 0) buffer.size else minOf(remaining, buffer.size.toLong()).toInt()
            val count = Os.read(descriptor.fileDescriptor, buffer, 0, maxRead)
            if (count == 0) {
                descriptor.parcelFileDescriptor.checkError()
                if (offsetBytesLeft > 0 || declaredBytesLeft > 0) throw EOFException("The local video ended before its declared length")
                break
            }
            if (offsetBytesLeft > 0) {
                offsetBytesLeft -= count
                continue
            }
            if (count > remainingBytes) throw IOException("Not enough cache space to read this video")
            output.write(buffer, 0, count)
            remainingBytes -= count
            if (declaredBytesLeft > 0) declaredBytesLeft -= count
        }
    }
}

private fun inspectVideo(
    descriptor: AssetFileDescriptor,
    checkDecoder: Boolean,
): ProbedVideo {
    val extractor = MediaExtractor()
    try {
        if (descriptor.declaredLength < 0) {
            extractor.setDataSource(descriptor.fileDescriptor)
        } else {
            extractor.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
        }
        val codecs = if (checkDecoder) MediaCodecList(MediaCodecList.REGULAR_CODECS) else null
        var videoMime: String? = null
        var audioMime: String? = null
        var hasAudio = false
        var durationUs = 0L
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            val video = mime.startsWith("video/")
            if (!video && !mime.startsWith("audio/")) continue
            if (!video) hasAudio = true
            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            durationUs = maxOf(durationUs, trackDurationUs)
            if (video && (trackDurationUs <= 0 || !format.hasPositiveVideoDimensions())) continue
            if (codecs != null && codecs.findDecoderForFormat(format) == null) continue
            extractor.selectTrack(index)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val hasSample = extractor.sampleTrackIndex == index && (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) == 0
            extractor.unselectTrack(index)
            if (!hasSample) continue
            if (video) videoMime = mime else audioMime = mime
        }
        if (videoMime == null || (hasAudio && audioMime == null)) {
            throw IOException("The source does not have readable video and audio tracks")
        }
        return ProbedVideo(durationUs, videoMime, audioMime)
    } finally {
        extractor.release()
    }
}

private fun MediaFormat.hasPositiveVideoDimensions(): Boolean =
    containsKey(MediaFormat.KEY_WIDTH) && getInteger(MediaFormat.KEY_WIDTH) > 0 &&
        containsKey(MediaFormat.KEY_HEIGHT) && getInteger(MediaFormat.KEY_HEIGHT) > 0

@OptIn(UnstableApi::class)
private suspend fun exportVideo(
    context: Context,
    input: Uri,
    output: File,
    bitrates: UploadVideoBitrates,
): ExportResult {
    val thread = HandlerThread("UploadVideoCompression").apply { start() }
    return try {
        val looper = thread.looper
        withContext(Handler(looper).asCoroutineDispatcher()) {
            // Only progressive local input can reach the decoder; no manifest or network data source.
            val dataSource =
                DataSource.Factory {
                    when (input.scheme) {
                        ContentResolver.SCHEME_CONTENT -> ContentDataSource(context)
                        ContentResolver.SCHEME_FILE -> FileDataSource()
                        ContentResolver.SCHEME_ANDROID_RESOURCE -> RawResourceDataSource(context)
                        else -> error("Unexpected nonlocal video URI")
                    }
                }
            val assetLoader =
                ExoPlayerAssetLoader.Factory(
                    context,
                    DefaultDecoderFactory.Builder(context).build(),
                    Clock.DEFAULT,
                    ProgressiveMediaSource.Factory(dataSource),
                )
            val settings = VideoEncoderSettings.Builder().setBitrate(bitrates.video)
            val preferredEncoders = EncoderSelector.DEFAULT.selectEncoderInfos(AVC)
            val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            // Android's VBR quality floor can ignore a low target; request CBR where the device supports it.
            if (preferredEncoders.all { EncoderUtil.isBitrateModeSupported(it, AVC, cbr) }) settings.setBitrateMode(cbr)
            val encoders = DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(settings.build())
            if (bitrates.audio > 0) {
                encoders.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrates.audio).build())
            }
            val transformer =
                Transformer
                    .Builder(context)
                    .setLooper(looper)
                    .setUsePlatformDiagnostics(false)
                    .setAssetLoaderFactory(assetLoader)
                    .setEncoderFactory(encoders.build())
                    // Review uses a seekable file; skip the streamable layout's 400,000-byte reservation.
                    .setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false))
                    .setVideoMimeType(AVC)
                    .setAudioMimeType(AAC)
                    .build()
            var finished = false
            try {
                suspendCancellableCoroutine { continuation ->
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    transformer.addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                finished = true
                                continuation.resume(exportResult)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                finished = true
                                continuation.resumeWithException(exportException)
                            }
                        },
                    )
                    transformer.start(MediaItem.fromUri(input), output.absolutePath)
                }
            } catch (failure: Exception) {
                if (!finished) {
                    // Cancel waits for release before the caller unlinks the files, without blocking the UI.
                    try {
                        transformer.cancel()
                    } catch (releaseFailure: Exception) {
                        failure.addSuppressed(releaseFailure)
                    }
                }
                throw failure
            }
        }
    } finally {
        thread.quitSafely()
    }
}
