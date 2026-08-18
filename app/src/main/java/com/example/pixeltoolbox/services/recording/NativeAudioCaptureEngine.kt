/*
 * Pixel Toolbox (像素工具箱)
 * Copyright (C) 2026 Pixel Toolbox Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 原生通话录音引擎（自研，替代 scrcpy-server 采集链路）：
 *   AudioRecord(VOICE_CALL) -> MediaCodec(AAC-LC) -> MediaMuxer(MPEG-4/.m4a)
 *
 * 全部在 App 进程内完成，不依赖 Shizuku / scrcpy-server / shell 进程。
 *
 * 前置条件：
 *   1. Xposed 模块（CallRecorderHooks）已在 system_server 放行 CAPTURE_AUDIO_OUTPUT；
 *   2. Manifest 已声明 CAPTURE_AUDIO_OUTPUT / CONTROL_INCALL_EXPERIENCE；
 *   3. 运行时 RECORD_AUDIO 权限已授予。
 */
package com.example.pixeltoolbox.services.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.example.pixeltoolbox.R
import com.example.pixeltoolbox.data.AppPreferences
import com.example.pixeltoolbox.data.call.CallDirection
import com.example.pixeltoolbox.data.call.EnrichedCallData
import com.example.pixeltoolbox.integrations.scrcpy.ScrcpyAudioCodec
import com.example.pixeltoolbox.system.storage.SafHelper
import com.example.pixeltoolbox.utils.AppLogger
import com.example.pixeltoolbox.utils.RecordingFileNameFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 原生采集录音管线。与 [AudioRecordingEngine] 的对外契约一致（startPipeline / release / cancel），
 * 供 [RecordingForegroundService] 直接替换使用。
 */
class NativeAudioCaptureEngine {

    private var sampleRate = 48000
    private var channelCount = 1
    private var bytesPerFrame = 2

    @Volatile
    var isPaused: Boolean = false

    var currentRecordingUri: Uri? = null

    var initializationMetadata: EnrichedCallData? = null

    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var audioTrackIndex = -1

    @Volatile
    private var stopped = false
    private var recordingThread: Thread? = null

    /** 录音线程抛出的异常（用于 startPipeline 阶段快速失败与 release 阶段记录）。 */
    @Volatile
    private var threadError: Throwable? = null

    fun startPipeline(context: Context, metadata: EnrichedCallData) {
        initializationMetadata = metadata
        val preferences = AppPreferences(context)
        val codecEnum = ScrcpyAudioCodec.AAC
        val folderUri = preferences.getRecordingFolderUri()
        val folderValid = SafHelper.isFolderValid(context, folderUri)
        val targetBitrate = preferences.getAudioBitRate().coerceAtLeast(128000)
        val sourceKey = preferences.getAudioSource()
        val preferredSource = if (sourceKey == "voice_communication") 7 else 4

        AppLogger.i("NativeAudioCaptureEngine: starting HD pipeline (targetBitrate=$targetBitrate, sourceKey=$sourceKey)")

        // 计算序列号（与 scrcpy 版一致：同日期/方向/号码前缀的文件数 + 1）。
        val datePrefix = SimpleDateFormat("yyyyMMdd", Locale.CANADA).format(Date())
        val directionStr = when (metadata.direction) {
            CallDirection.INCOMING -> "来电"
            CallDirection.OUTGOING -> "去电"
        }
        val phoneStr = metadata.getBestNumber()
        val sequencePrefix = "${datePrefix}_${directionStr}_${phoneStr}_"
        val existingCount = SafHelper.countExistingRecordingFiles(context, folderUri, sequencePrefix)
        val sequence = String.format(Locale.US, "%03d", existingCount + 1)

        val fileName = RecordingFileNameFormatter.formatFileName(context, metadata, codecEnum, sequence = sequence)

        val safResult = if (folderValid) {
            SafHelper.createAudioFile(context, folderUri!!, fileName, codecEnum.mimeType)
        } else {
            SafHelper.createAudioFileInDownloads(context, fileName, codecEnum.mimeType)
        } ?: throw PipelineInitializationException(
            userFriendlyMessage = context.getString(R.string.recording_error_file_creation),
            technicalLogMessage = "Failed to create audio file (folderValid=$folderValid)"
        )

        currentRecordingUri = safResult.uri
        outputPfd = safResult.descriptor
        AppLogger.d("NativeAudioCaptureEngine: created file ${safResult.uri}")

        // 1. AudioRecord（支持 48kHz HD 采样率优先，自动适配双声道/单声道）。
        audioRecord = createAudioRecord(preferredSource)

        // 2. MediaCodec AAC-LC 高清编码器。
        mediaCodec = createAacEncoder(sampleRate, targetBitrate)

        // 3. MediaMuxer（MPEG-4/.m4a）。
        try {
            mediaMuxer = MediaMuxer(safResult.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Failed to create MediaMuxer",
                cause = t
            )
        }

        // 4. 启动采集线程。
        try {
            mediaCodec?.start()
            audioRecord?.startRecording()
        } catch (t: Throwable) {
            releaseInternal()
            throw PipelineInitializationException(
                userFriendlyMessage = context.getString(R.string.recording_error_start_failed),
                technicalLogMessage = "Failed to start capture",
                cause = t
            )
        }

        stopped = false
        threadError = null
        recordingThread = thread(name = "NativeAudioCapture") { encodeLoop() }
        AppLogger.i("NativeAudioCaptureEngine: HD pipeline started (sampleRate=$sampleRate, channels=$channelCount, bitrate=$targetBitrate)")
    }

    /** 释放所有资源（幂等，供 release / cancel / 异常路径复用）。 */
    fun release() {
        releaseInternal()
    }

    fun cancel(context: Context) {
        releaseInternal()
        try {
            currentRecordingUri?.let { uri ->
                DocumentFile.fromSingleUri(context, uri)?.delete()
            }
            AppLogger.d("NativeAudioCaptureEngine: cleaned up empty file")
        } catch (t: Throwable) {
            AppLogger.w("NativeAudioCaptureEngine: failed to cleanup file", t)
        }
    }

    private fun createAudioRecord(preferredSource: Int): AudioRecord {
        val candidatesSources = intArrayOf(preferredSource, 4, 7, 6)
        val candidateSampleRates = intArrayOf(48000, 44100, 32000, 24000, 16000, 8000)
        val candidateChannels = intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)

        for (source in candidatesSources.distinct()) {
            for (sr in candidateSampleRates) {
                for (channelConfig in candidateChannels) {
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(sr, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                        if (minBuf <= 0) continue
                        val rec = AudioRecord(source, sr, channelConfig, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384))
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            sampleRate = sr
                            channelCount = if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
                            bytesPerFrame = channelCount * 2
                            AppLogger.i("NativeAudioCaptureEngine: initialized AudioRecord source=$source sr=$sr channels=$channelCount")
                            return rec
                        }
                        rec.release()
                    } catch (t: Throwable) {
                        AppLogger.w("NativeAudioCaptureEngine: AudioRecord source=$source sr=$sr channels=$channelConfig failed: ${t.message}")
                    }
                }
            }
        }
        throw PipelineInitializationException(
            userFriendlyMessage = "无法初始化 HD 通话录音（音频源不可用，请确认 Root/Xposed 权限放行）",
            technicalLogMessage = "AudioRecord init failed for all candidate sample rates"
        )
    }

    private fun createAacEncoder(sr: Int, bitrate: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }
        return try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (t: Throwable) {
            throw PipelineInitializationException(
                userFriendlyMessage = "无法初始化 AAC 高清编码器",
                technicalLogMessage = "AAC encoder init failed",
                cause = t
            )
        }
    }

    private fun encodeLoop() {
        try {
            val record = audioRecord ?: return
            val codec = mediaCodec ?: return
            val muxer = mediaMuxer ?: return
            val bufferInfo = MediaCodec.BufferInfo()
            var pts = 0L
            var eosQueued = false

            while (!stopped) {
                // ---- 输入：读 PCM 喂给编码器 ----
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)
                    if (buf != null) {
                        val n = try {
                            record.read(buf, buf.remaining())
                        } catch (t: Throwable) {
                            AppLogger.w("NativeAudioCaptureEngine: read error ${t.message}")
                            -1
                        }
                        if (n > 0) {
                            if (isPaused) {
                                // 暂停：丢弃本帧，但需归还 input buffer（空帧）。
                                codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                            } else {
                                codec.queueInputBuffer(inIndex, 0, n, pts, 0)
                                pts += n / bytesPerFrame * 1_000_000L / sampleRate
                            }
                        } else if (n < 0) {
                            // 读取错误，标记 EOS 结束本次编码。
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }

                // ---- 输出：取编码后的 AAC 帧写 muxer ----
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        audioTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 && audioTrackIndex >= 0) {
                            val isCsd = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isCsd) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(audioTrackIndex, outBuf, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }

            // 若未显式 EOS，补发 EOS 并排空剩余输出。
            if (!eosQueued) {
                try {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } catch (t: Throwable) {
                    AppLogger.w("NativeAudioCaptureEngine: EOS queue failed: ${t.message}")
                }
                drainOutput(codec, muxer, bufferInfo)
            }
        } catch (t: Throwable) {
            threadError = t
            AppLogger.e("NativeAudioCaptureEngine: encode loop error", t)
        }
    }

    private fun drainOutput(codec: MediaCodec, muxer: MediaMuxer, bufferInfo: MediaCodec.BufferInfo) {
        try {
            while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0 && audioTrackIndex >= 0) {
                            val isCsd = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isCsd) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(audioTrackIndex, outBuf, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break
                }
            }
        } catch (t: Throwable) {
            AppLogger.w("NativeAudioCaptureEngine: drain output error: ${t.message}")
        }
    }

    private fun releaseInternal() {
        stopped = true
        try {
            recordingThread?.join(1500)
        } catch (t: Throwable) {
            AppLogger.w("NativeAudioCaptureEngine: join interrupted: ${t.message}")
        }
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (t: Throwable) {
        }
        try {
            mediaCodec?.stop()
        } catch (t: Throwable) {
        }
        try {
            mediaCodec?.release()
        } catch (t: Throwable) {
        }
        try {
            mediaMuxer?.stop()
        } catch (t: Throwable) {
        }
        try {
            mediaMuxer?.release()
        } catch (t: Throwable) {
        }
        try {
            outputPfd?.close()
        } catch (t: Throwable) {
        }
        audioRecord = null
        mediaCodec = null
        mediaMuxer = null
        outputPfd = null
        audioTrackIndex = -1
        AppLogger.i("NativeAudioCaptureEngine: resources released")
    }
}
