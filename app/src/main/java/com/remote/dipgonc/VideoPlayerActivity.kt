package com.remote.dipgonc

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "video_url"
        private const val FIVE_WATERMARK_HEIGHT = 64f / 2224f
        private const val STRONG_SHAKE = 5
        private const val STRONG_VIBRATION = 10
        private const val REQUEST_WRITE_STORAGE = 100
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var metadataOverlay: VideoMetadataOverlayView
    private lateinit var btnDownload: Button
    private lateinit var videoUrl: String
    private lateinit var gestureDetector: GestureDetector
    private var selectedRegion = 0
    private var isFiveLayout = false
    private var videoMetadata: VideoMetadataOverlayView.Metadata? = null
    private var videoInfoJson: JSONObject? = null
    private var metadataSource: MetadataSource? = null
    private var controllerVisible = false
    private var downloadPendingForPermission = false
    private var downloading = false
    private val handler = Handler(Looper.getMainLooper())
    private val showControllerRunnable = Runnable {
        playerView.showController()
    }
    private val overlayUpdateRunnable = object : Runnable {
        override fun run() {
            updateMetadataOverlay()
            handler.postDelayed(this, 250)
        }
    }

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
        videoUrl = url

        playerView = findViewById(R.id.playerView)
        metadataOverlay = findViewById(R.id.metadataOverlay)
        btnDownload = findViewById(R.id.btnDownload)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controllerVisible = visibility == View.VISIBLE
            btnDownload.visibility = if (controllerVisible) View.VISIBLE else View.GONE
        })
        btnDownload.setOnClickListener { startDownload() }
        metadataOverlay.setOnTimelineSeekListener { seekToWallTime(it) }
        metadataSource = parseMetadataSource(url)
        loadVideoMetadata()
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
        handler.post(overlayUpdateRunnable)
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                handler.removeCallbacks(showControllerRunnable)
                if (selectedRegion != 0) {
                    updateVideoRegion(0)
                } else {
                    val region = hitTestRegion(e.x, e.y)
                    if (region > 0) {
                        updateVideoRegion(region)
                    }
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handler.removeCallbacks(showControllerRunnable)
                if (controllerVisible) {
                    playerView.hideController()
                } else {
                    handler.postDelayed(showControllerRunnable, 120)
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
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
            updateMetadataOverlay()
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
        updateMetadataOverlay()
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

    private fun updateMetadataOverlay() {
        val data = videoMetadata
        val exoPlayer = player
        if (data == null || exoPlayer == null) {
            metadataOverlay.setPlaybackState(0L, selectedRegion, isFiveLayout)
            return
        }
        val currentWallMs = currentWallMs(data, exoPlayer)
        metadataOverlay.setPlaybackState(currentWallMs, selectedRegion, isFiveLayout)
    }

    private fun currentWallMs(
        data: VideoMetadataOverlayView.Metadata,
        exoPlayer: ExoPlayer
    ): Long {
        val duration = exoPlayer.duration
        val segmentDuration = data.endMs - data.startMs
        if (data.startMs <= 0L) {
            return exoPlayer.currentPosition
        }
        if (duration > 0L && segmentDuration > 0L && abs(segmentDuration - duration) > 1000L) {
            val ratio = (exoPlayer.currentPosition.toDouble() / duration).coerceIn(0.0, 1.0)
            return data.startMs + (segmentDuration * ratio).toLong()
        }
        return data.startMs + exoPlayer.currentPosition
    }

    private fun seekToWallTime(wallMs: Long) {
        val data = videoMetadata ?: return
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        val segmentDuration = data.endMs - data.startMs
        val position = if (data.startMs > 0L &&
            duration > 0L &&
            segmentDuration > 0L &&
            abs(segmentDuration - duration) > 1000L
        ) {
            val ratio = ((wallMs - data.startMs).toDouble() / segmentDuration).coerceIn(0.0, 1.0)
            (duration * ratio).toLong()
        } else if (data.startMs > 0L) {
            wallMs - data.startMs
        } else {
            wallMs
        }
        val maxPosition = if (duration > 0L) duration else Long.MAX_VALUE
        exoPlayer.seekTo(position.coerceIn(0L, maxPosition))
        updateMetadataOverlay()
    }

    private fun loadVideoMetadata() {
        val source = metadataSource ?: return
        val cachedJson = VideoMetadataCache.get(source.key)
        if (cachedJson != null) {
            runCatching { parseVideoInfo(JSONObject(cachedJson), source) }.getOrNull()?.let { metadata ->
                videoInfoJson = JSONObject(cachedJson)
                videoMetadata = metadata
                metadataOverlay.setMetadata(metadata)
                updateMetadataOverlay()
                return
            }
        }
        Thread {
            val metadata = runCatching { fetchVideoMetadata(source) }.getOrNull()
            if (metadata != null) {
                runOnUiThread {
                    videoMetadata = metadata
                    metadataOverlay.setMetadata(metadata)
                    updateMetadataOverlay()
                }
            }
        }.start()
    }

    private fun fetchVideoMetadata(source: MetadataSource): VideoMetadataOverlayView.Metadata? {
        val connection = URL(source.infoUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        return try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(json)
            videoInfoJson = jsonObject
            parseVideoInfo(jsonObject, source)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseVideoInfo(
        json: JSONObject,
        source: MetadataSource
    ): VideoMetadataOverlayView.Metadata {
        val labels = parseLabels(json.optJSONArray("labels"))
        val motions = parseMotions(json, source.startMs, source.endMs)
        val inferredEnd = when {
            source.endMs > source.startMs -> source.endMs
            labels.isNotEmpty() -> labels.maxOf { it.timeMs } + 1000L
            motions.isNotEmpty() -> motions.maxOf { it.endMs }
            else -> source.startMs
        }
        return VideoMetadataOverlayView.Metadata(
            startMs = source.startMs,
            endMs = inferredEnd,
            labels = labels.sortedBy { it.timeMs },
            motions = motions
        )
    }

    private fun parseLabels(items: JSONArray?): List<VideoMetadataOverlayView.Label> {
        if (items == null) return emptyList()
        val labels = ArrayList<VideoMetadataOverlayView.Label>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val timeMs = item.optLong("timeMS", Long.MIN_VALUE)
            if (timeMs == Long.MIN_VALUE) continue
            val results = item.optJSONArray("results")
                ?: item.optJSONArray("detectorResults")
                ?: continue
            for (j in 0 until results.length()) {
                val result = results.optJSONObject(j) ?: continue
                val box = parseBox(result) ?: continue
                labels.add(
                    VideoMetadataOverlayView.Label(
                        timeMs = timeMs,
                        kind = labelKind(
                            result.optString("className")
                                .ifBlank { result.optString("name") }
                                .ifBlank { result.optString("label") }
                        ),
                        box = box
                    )
                )
            }
        }
        return labels
    }

    private fun parseMotions(
        json: JSONObject,
        startMs: Long,
        endMs: Long
    ): List<VideoMetadataOverlayView.Motion> {
        val raw = json.optJSONArray("motions") ?: json.optJSONArray("ms") ?: return emptyList()
        val intervalMs = max(1, json.optInt("motionIntervalSec", json.optInt("mi", 1))) * 1000L
        val segmentEnd = if (endMs > startMs) endMs else Long.MAX_VALUE
        val motions = ArrayList<VideoMetadataOverlayView.Motion>()
        var i = 0
        while (i + 4 < raw.length()) {
            val offsetMs = raw.optLong(i, -1L) * 1000L
            if (offsetMs >= 0L) {
                val markStart = startMs + offsetMs
                val markEnd = min(segmentEnd, markStart + intervalMs * 2)
                val gyro = raw.optInt(i + 1, 0)
                val g = raw.optInt(i + 3, 0)
                if (gyro > 0) {
                    addMotion(motions, VideoMetadataOverlayView.MotionKind.SHAKE, markStart, markEnd, gyro > STRONG_SHAKE)
                }
                if (g > 0) {
                    addMotion(motions, VideoMetadataOverlayView.MotionKind.VIBRATION, markStart, markEnd, g > STRONG_VIBRATION)
                }
            }
            i += 5
        }
        return motions
    }

    private fun addMotion(
        motions: ArrayList<VideoMetadataOverlayView.Motion>,
        kind: VideoMetadataOverlayView.MotionKind,
        startMs: Long,
        endMs: Long,
        alarm: Boolean
    ) {
        if (endMs <= startMs) return
        val last = motions.lastOrNull()
        if (last != null && last.kind == kind && last.alarm == alarm && startMs - last.endMs <= 1000L) {
            motions[motions.lastIndex] = last.copy(endMs = max(last.endMs, endMs))
            return
        }
        motions.add(VideoMetadataOverlayView.Motion(startMs, endMs, kind, alarm))
    }

    private fun parseBox(result: JSONObject): VideoMetadataOverlayView.Box? {
        val nested = result.opt("box") ?: result.opt("rect") ?: result.opt("bbox")
        if (nested is JSONArray && nested.length() >= 4) {
            return makeBox(nested.optDouble(0), nested.optDouble(1), nested.optDouble(2), nested.optDouble(3))
        }
        if (nested is JSONObject) {
            return parseBox(nested)
        }
        val x = pickNumber(result, "x", "X", "left", "Left") ?: return null
        val y = pickNumber(result, "y", "Y", "top", "Top") ?: return null
        var width = pickNumber(result, "width", "Width", "w", "W")
        var height = pickNumber(result, "height", "Height", "h", "H")
        val right = pickNumber(result, "right", "Right")
        val bottom = pickNumber(result, "bottom", "Bottom")
        if (width == null && right != null) width = right - x
        if (height == null && bottom != null) height = bottom - y
        return makeBox(x, y, width, height)
    }

    private fun pickNumber(json: JSONObject, vararg names: String): Double? {
        for (name in names) {
            if (!json.has(name)) continue
            val value = json.opt(name)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun makeBox(
        x: Double?,
        y: Double?,
        width: Double?,
        height: Double?
    ): VideoMetadataOverlayView.Box? {
        if (x == null || y == null || width == null || height == null) return null
        val left = x.toFloat().coerceIn(0f, 1f)
        val top = y.toFloat().coerceIn(0f, 1f)
        val right = (x + width).toFloat().coerceIn(0f, 1f)
        val bottom = (y + height).toFloat().coerceIn(0f, 1f)
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return null
        return VideoMetadataOverlayView.Box(left, top, w, h)
    }

    private fun labelKind(name: String): VideoMetadataOverlayView.Kind {
        val lower = name.lowercase()
        if (lower.contains("person") || lower.contains("people") || lower.contains("human")) {
            return VideoMetadataOverlayView.Kind.PERSON
        }
        if (lower.contains("car") || lower.contains("truck") || lower.contains("bus") ||
            lower.contains("vehicle") || lower.contains("motor") || lower.contains("bicycle")
        ) {
            return VideoMetadataOverlayView.Kind.VEHICLE
        }
        return VideoMetadataOverlayView.Kind.OTHER
    }

    private fun parseMetadataSource(url: String): MetadataSource? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        if (!path.endsWith("/api/videoStream", ignoreCase = true)) return null
        val key = uri.getQueryParameter("key") ?: return null
        val builder = uri.buildUpon().path(path.replace("/api/videoStream", "/api/videoInfo"))
        builder.clearQueryCompat()
        builder.appendQueryParameter("key", key)
        uri.getQueryParameter("auth")?.let { builder.appendQueryParameter("auth", it) }
        val range = parseRangeFromKey(key)
        return MetadataSource(key, builder.build().toString(), range.first, range.second)
    }

    private fun Uri.Builder.clearQueryCompat(): Uri.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            clearQuery()
        } else {
            encodedQuery(null)
        }
        return this
    }

    private fun parseRangeFromKey(key: String): Pair<Long, Long> {
        val decoded = runCatching {
            val flags = Base64.DEFAULT or Base64.URL_SAFE
            String(Base64.decode(key, flags))
        }.getOrDefault("")
        val name = decoded.substringAfterLast('/')
        val match = Regex("""(\d{10,})_(\d{10,})""").find(name)
        val start = match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val end = match?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
        return start to end
    }

    private fun startDownload() {
        if (downloading) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            downloadPendingForPermission = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE)
            return
        }
        downloadCurrentVideo()
    }

    @OptIn(UnstableApi::class)
    private fun downloadCurrentVideo() {
        downloading = true
        btnDownload.isEnabled = false
        btnDownload.text = "下载中"
        val filename = downloadFileName()
        Thread {
            val result = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStore(filename)
                } else {
                    saveToLegacyDownloads(filename)
                }
            }
            runOnUiThread {
                downloading = false
                btnDownload.isEnabled = true
                btnDownload.text = "下载"
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { "已下载到 $it" },
                        onFailure = { "下载失败：${it.message ?: "未知错误"}" }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    @OptIn(UnstableApi::class)
    private fun saveToMediaStore(filename: String): String {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(filename))
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DipGonc")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                VideoCacheManager.writeCacheFirst(this, videoUrl, output)
            } ?: error("无法打开下载文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Download/DipGonc/$filename"
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @OptIn(UnstableApi::class)
    private fun saveToLegacyDownloads(filename: String): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DipGonc")
        if (!dir.exists() && !dir.mkdirs()) {
            error("无法创建下载目录")
        }
        val file = uniqueFile(dir, filename)
        FileOutputStream(file).use { output ->
            VideoCacheManager.writeCacheFirst(this, videoUrl, output)
        }
        return file.absolutePath
    }

    private fun uniqueFile(dir: File, filename: String): File {
        val dot = filename.lastIndexOf('.')
        val name = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var file = File(dir, filename)
        var index = 1
        while (file.exists()) {
            file = File(dir, "$name-$index$ext")
            index++
        }
        return file
    }

    private fun downloadFileName(): String {
        val source = metadataSource
        val start = source?.startMs ?: 0L
        val end = source?.endMs ?: 0L
        val quality = qualityName()
        val rawName = if (start > 0L && end > start) {
            "${formatTimeForName(start)}-${formatTimeForName(end)}-$quality.mp4"
        } else {
            "dipgonc-video-$quality.mp4"
        }
        return rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    private fun mimeType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "flv" -> "video/x-flv"
            "m4v" -> "video/x-m4v"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
    }

    private fun qualityName(): String {
        val uriQuality = runCatching { Uri.parse(videoUrl).getQueryParameter("quality") }.getOrNull()
        val metadataQuality = videoInfoJson?.optString("quality")?.takeIf { it.isNotBlank() }
        return sanitizeNamePart(qualityLabel(uriQuality ?: metadataQuality ?: "origin"))
    }

    private fun qualityLabel(value: String): String {
        return when (value.lowercase()) {
            "low" -> "省流"
            "standard" -> "均衡"
            "origin" -> "原画"
            else -> value
        }
    }

    private fun formatTimeForName(ms: Long): String {
        return SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA).format(Date(ms))
    }

    private fun sanitizeNamePart(value: String): String {
        return value.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE && downloadPendingForPermission) {
            downloadPendingForPermission = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                downloadCurrentVideo()
            } else {
                Toast.makeText(this, "没有存储权限，无法下载", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(showControllerRunnable)
        handler.removeCallbacks(overlayUpdateRunnable)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private data class MetadataSource(
        val key: String,
        val infoUrl: String,
        val startMs: Long,
        val endMs: Long
    )
}
