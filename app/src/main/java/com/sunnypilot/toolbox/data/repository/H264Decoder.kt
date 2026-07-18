package com.sunnypilot.toolbox.data.repository

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H264 硬件解码器 — 使用 MediaCodec 解码 H264 流到 Surface
 *
 * 原理：
 *   1. 创建 H264 解码器（MediaCodec.createDecoderByType）
 *   2. 配置 Surface（输出到 SurfaceView/TextureView）
 *   3. 喂 H264 帧给解码器（feedFrame）
 *   4. 解码器输出到 Surface（自动渲染）
 *
 * 优势：
 *   - 使用硬件解码器，性能极佳
 *   - 直接输出到 Surface，无需 CPU 拷贝
 *   - 7.0 安卓完全支持
 */
class H264Decoder(
    private val surface: Surface,
    private val onDecoderReady: (() -> Unit)? = null,
    private val onDecoderError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "H264Decoder"
        private const val MIME_TYPE = "video/avc"
    }

    private var codec: MediaCodec? = null
    private var isConfigured = false
    private val isRunning = AtomicBoolean(false)
    
    // 配置参数（初始值，收到第一帧后可能更新）
    private var width = 1280
    private var height = 720
    private var frameRate = 30
    private var profile = 1  // AVCProfileBaseline
    private var level = 31   // AVCLevel31

    /**
     * 配置解码器
     *
     * @param width 视频宽度
     * @param height 视频高度
     * @param frameRate 帧率
     * @param profile H264 配置文件（1=Baseline, 2=Main, 3=High）
     * @param level H264 级别
     */
    fun configure(
        width: Int = 1280,
        height: Int = 720,
        frameRate: Int = 30,
        profile: Int = 1,
        level: Int = 31
    ): Boolean {
        try {
            // 如果已配置且参数相同，不需要重新配置
            if (isConfigured && this.width == width && this.height == height) {
                return true
            }

            // 释放旧解码器
            release()

            this.width = width
            this.height = height
            this.frameRate = frameRate
            this.profile = profile
            this.level = level

            // 创建解码器
            codec = MediaCodec.createDecoderByType(MIME_TYPE).apply {
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodec.INFO_FORMAT_UNKNOWN)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                    setInteger(MediaFormat.KEY_LEVEL, level)
                }

                // 配置解码器，输出到 Surface
                configure(format, surface, null, 0)
                start()

                Log.d(TAG, "Decoder configured: ${width}x${height}, fps=$frameRate")
                isConfigured = true
                isRunning.set(true)
                onDecoderReady?.invoke()
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder: ${e.message}")
            onDecoderError?.invoke("解码器配置失败: ${e.message}")
            return false
        }
    }

    /**
     * 喂 H264 帧给解码器
     *
     * @param h264Data H264 帧数据（原始 bytes）
     * @return 是否成功喂入
     */
    fun feedFrame(h264Data: ByteArray): Boolean {
        if (!isConfigured || !isRunning.get()) {
            Log.w(TAG, "Decoder not configured or not running")
            return false
        }

        try {
            val codecInstance = codec ?: return false

            // 从 SPS/PPS 获取视频参数（如果还没配置）
            if (!isConfigured) {
                extractVideoParams(h264Data)
                configure()
            }

            // 获取输入缓冲区
            val index = codecInstance.dequeueInputBuffer(10000)
            if (index >= 0) {
                val inputBuffer = codecInstance.getInputBuffer(index)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(h264Data)
                    
                    // 计算时间戳（简单递增）
                    val timestamp = System.nanoTime() / 1000
                    
                    codecInstance.queueInputBuffer(
                        index,
                        0,
                        h264Data.size,
                        timestamp,
                        0
                    )
                }
            }

            // 处理输出（解码后的帧会自动渲染到 Surface）
            processOutput()

            return true

        } catch (e: Exception) {
            Log.w(TAG, "Failed to feed frame: ${e.message}")
            return false
        }
    }

    /**
     * 处理解码器输出（解码后的帧自动渲染到 Surface）
     */
    private fun processOutput() {
        try {
            val codecInstance = codec ?: return
            val info = MediaCodec.BufferInfo()

            while (true) {
                val index = codecInstance.dequeueOutputBuffer(info, 1000)
                
                when {
                    index >= 0 -> {
                        // 渲染到 Surface（true = 立即渲染）
                        codecInstance.releaseOutputBuffer(index, true)
                        
                        // 如果收到结束标记，停止解码
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "End of stream reached")
                            isRunning.set(false)
                            return
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        // 输出缓冲区格式改变，重新配置（通常不需要处理）
                        Log.d(TAG, "Output buffers changed")
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 输出格式改变（通常在第一帧时发生）
                        val newFormat = codecInstance.outputFormat
                        Log.d(TAG, "Output format changed: ${newFormat.toString()}")
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 没有可用的输出缓冲区，退出循环
                        break
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process output: ${e.message}")
        }
    }

    /**
     * 从 H264 数据中提取视频参数（SPS/PPS）
     */
    private fun extractVideoParams(h264Data: ByteArray) {
        try {
            // 查找 SPS（Sequence Parameter Set）
            // H264 NAL 单元开始码：00 00 00 01 或 00 00 01
            val naluStart = findNalStart(h264Data)
            if (naluStart < 0) return

            // SPS 的 NAL 类型是 7（0x67）
            if (h264Data[naluStart + 4] == 0x67.toByte()) {
                // 简单的 SPS 解析（只提取基本信息）
                val sps = h264Data
                val spsStart = naluStart + 5 // 跳过开始码

                // 提取 profile 和 level（简化版）
                if (spsStart + 2 < sps.size) {
                    profile = sps[spsStart + 1].toInt() and 0xFF
                    // profile_idc: 66=Baseline, 77=Main, 100=High
                    when (profile) {
                        66 -> profile = 1
                        77 -> profile = 2
                        100 -> profile = 3
                    }
                }

                // 提取 width/height（简化版，从 SPS 解析）
                // 实际项目中可能需要更完整的 SPS 解析器
                // 这里用默认值，或者从 openpilot 参数获取
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video params: ${e.message}")
        }
    }

    /**
     * 查找 NAL 单元开始码
     */
    private fun findNalStart(data: ByteArray): Int {
        for (i in 0 until data.size - 4) {
            if (data[i] == 0x00.toByte() && 
                data[i + 1] == 0x00.toByte() && 
                data[i + 2] == 0x00.toByte() && 
                data[i + 3] == 0x01.toByte()) {
                return i
            }
            if (data[i] == 0x00.toByte() && 
                data[i + 1] == 0x00.toByte() && 
                data[i + 2] == 0x01.toByte()) {
                return i
            }
        }
        return -1
    }

    /**
     * 设置新的视频尺寸（当摄像头切换时可能需要）
     */
    fun resize(width: Int, height: Int) {
        Log.d(TAG, "Resizing decoder: ${width}x${height}")
        configure(width, height, frameRate, profile, level)
    }

    /**
     * 释放解码器
     */
    fun release() {
        try {
            isRunning.set(false)
            codec?.let {
                try {
                    it.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop codec: ${e.message}")
                }
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release codec: ${e.message}")
                }
            }
            codec = null
            isConfigured = false
            Log.d(TAG, "Decoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release decoder: ${e.message}")
        }
    }

    /**
     * 检查解码器是否就绪
     */
    fun isReady(): Boolean = isConfigured && isRunning.get()

    /**
     * 获取当前视频尺寸
     */
    fun getVideoSize(): Pair<Int, Int> = Pair(width, height)
}
