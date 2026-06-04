package com.remote.dipgonc

import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "video_url"
        private const val FIVE_WATERMARK_HEIGHT = 64f / 2224f
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var gestureDetector: GestureDetector
    private var selectedRegion = 0
    private var isFiveLayout = false

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "视频地址为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        playerView = findViewById(R.id.playerView)
        setupGestures()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCacheManager.dataSourceFactory(this)))
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        isFiveLayout = videoSize.height > 0 && videoSize.width > 0 &&
                            videoSize.height > videoSize.width
                        updateVideoRegion(0)
                    }
                })
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val region = hitTestRegion(e.x, e.y)
                updateVideoRegion(if (region == selectedRegion) 0 else region)
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun hitTestRegion(x: Float, y: Float): Int {
        val width = playerView.width.toFloat()
        val height = playerView.height.toFloat()
        if (width <= 0f || height <= 0f) return 0

        if (isFiveLayout) {
            val nx = x / width
            val ny = y / height
            val watermarkBottom = FIVE_WATERMARK_HEIGHT
            val recordBottom = watermarkBottom + (1f - watermarkBottom) * 0.5f
            val panoMidY = watermarkBottom + (1f - watermarkBottom) * 0.75f
            return when {
                ny < watermarkBottom -> 0
                ny < recordBottom -> 1
                ny < panoMidY && nx < 0.5f -> 2
                ny < panoMidY -> 3
                nx < 0.5f -> 4
                else -> 5
            }
        }

        return when {
            x < width / 2f && y < height / 2f -> 1
            x >= width / 2f && y < height / 2f -> 2
            x < width / 2f -> 3
            else -> 4
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateVideoRegion(region: Int) {
        selectedRegion = region
        val surfaceView = playerView.videoSurfaceView ?: return
        val params = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return

        if (region <= 0) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.leftMargin = 0
            params.topMargin = 0
            player?.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            surfaceView.layoutParams = params
            return
        }

        val rect = if (isFiveLayout) fiveRegion(region) else fourRegion(region)
        val viewWidth = playerView.width
        val viewHeight = playerView.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val sourceWidth = rect[2] - rect[0]
        val sourceHeight = rect[3] - rect[1]
        val surfaceWidth = (viewWidth / sourceWidth).toInt()
        val surfaceHeight = (viewHeight / sourceHeight).toInt()
        params.width = surfaceWidth
        params.height = surfaceHeight
        params.leftMargin = (-rect[0] * surfaceWidth).toInt()
        params.topMargin = (-rect[1] * surfaceHeight).toInt()
        player?.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        surfaceView.layoutParams = params
    }

    private fun fourRegion(region: Int): FloatArray {
        return when (region) {
            1 -> floatArrayOf(0f, 0f, 0.5f, 0.5f)
            2 -> floatArrayOf(0.5f, 0f, 1f, 0.5f)
            3 -> floatArrayOf(0f, 0.5f, 0.5f, 1f)
            else -> floatArrayOf(0.5f, 0.5f, 1f, 1f)
        }
    }

    private fun fiveRegion(region: Int): FloatArray {
        val watermarkBottom = FIVE_WATERMARK_HEIGHT
        val recordBottom = watermarkBottom + (1f - watermarkBottom) * 0.5f
        val panoMidY = watermarkBottom + (1f - watermarkBottom) * 0.75f
        return when (region) {
            1 -> floatArrayOf(0f, watermarkBottom, 1f, recordBottom)
            2 -> floatArrayOf(0f, recordBottom, 0.5f, panoMidY)
            3 -> floatArrayOf(0.5f, recordBottom, 1f, panoMidY)
            4 -> floatArrayOf(0f, panoMidY, 0.5f, 1f)
            else -> floatArrayOf(0.5f, panoMidY, 1f, 1f)
        }
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
