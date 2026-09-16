package com.emprendedorlatam.embymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class ScreenMirrorService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var server: StreamServer? = null
    private val publisher = StreamPublisher()
    private val muxer = TsMuxer()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var frames = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Inicializando"))

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent = intent?.getParcelableExtra(EXTRA_DATA) ?: run {
            stopSelf(); return START_NOT_STICKY
        }
        val port = intent.getIntExtra(EXTRA_PORT, 8088)
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val fps = intent.getIntExtra(EXTRA_FPS, 24)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 2_500_000)

        runCatching {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            server = StreamServer(port, publisher).also { it.start(5_000, false) }
            startEncoder(width, height, fps, bitrate)
            MirrorStateStore.update(
                MirrorState(
                    running = true,
                    message = "capturando en http://${NetUtils.firstIpv4Address()}:$port/channels.m3u",
                    m3uUrl = "http://${NetUtils.firstIpv4Address()}:$port/channels.m3u"
                )
            )
            updateNotification("Emitiendo en puerto $port")
        }.onFailure {
            MirrorStateStore.update(MirrorState(running = false, message = "error: ${it.message}"))
            stopSelf()
        }

        return START_STICKY
    }

    private fun startEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        handleEncodedBuffer(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    MirrorStateStore.update(MirrorState(running = false, message = "codec error: ${e.message}"))
                    stopSelf()
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    sps = format.getByteBuffer("csd-0")?.toByteArraySafe()
                    pps = format.getByteBuffer("csd-1")?.toByteArraySafe()
                }
            })
            start()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "emby-mirror",
            width,
            height,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
    }

    private fun handleEncodedBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        val nals = H264Utils.splitLengthPrefixed(buffer)
        if (nals.isEmpty()) return
        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || nals.any { H264Utils.isIdr(it) }
        val fullNals = buildList {
            if (isKey) {
                sps?.let { add(it) }
                pps?.let { add(it) }
            }
            addAll(nals)
        }
        val sendTables = frames % 24L == 0L || isKey
        val packets = muxer.muxAccessUnit(fullNals, info.presentationTimeUs, sendTables)
        packets.forEach { publisher.broadcast(it) }
        frames++
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { server?.stop() }
        runCatching { virtualDisplay?.release() }
        runCatching { inputSurface?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { mediaProjection?.stop() }
        publisher.closeAll()
        MirrorStateStore.update(MirrorState(running = false, message = "detenido"))
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "emby_mirror_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Emby Mirror", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenMirrorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Emby Mirror")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openIntent)
            .addAction(0, "Detener", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIFICATION_ID = 5511
        private const val ACTION_STOP = "com.emprendedorlatam.embymirror.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE = "bitrate"

        fun startIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            port: Int,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int
        ): Intent {
            return Intent(context, ScreenMirrorService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_BITRATE, bitrate)
            }
        }
    }
}

private fun ByteBuffer.toByteArraySafe(): ByteArray {
    val dup = duplicate()
    val bytes = ByteArray(dup.remaining())
    dup.get(bytes)
    return bytes
}
