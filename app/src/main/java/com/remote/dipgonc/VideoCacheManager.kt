package com.remote.dipgonc

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

@UnstableApi
object VideoCacheManager {

    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L

    @Volatile
    private var cache: SimpleCache? = null

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        return CacheDataSource.Factory()
            .setCache(getCache(context.applicationContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun writeCachedIfComplete(context: Context, url: String, output: OutputStream): Boolean {
        val cache = getCache(context.applicationContext)
        val dataSpec = dataSpec(url)
        val key = CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0L || cache.getCachedBytes(key, 0L, length) < length) {
            return false
        }
        var copied = 0L
        for (span in cache.getCachedSpans(key)) {
            val file = span.file ?: return false
            FileInputStream(file).use { input ->
                input.copyTo(output)
            }
            copied += span.length
        }
        return copied >= length
    }

    fun writeCacheFirst(context: Context, url: String, output: OutputStream) {
        if (writeCachedIfComplete(context, url, output)) {
            return
        }
        val dataSource = dataSourceFactory(context.applicationContext).createDataSource()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            dataSource.open(dataSpec(url))
            while (true) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
        } finally {
            dataSource.close()
        }
    }

    private fun dataSpec(url: String): DataSpec {
        return DataSpec.Builder()
            .setUri(Uri.parse(url))
            .build()
    }

    private fun getCache(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val databaseProvider = StandaloneDatabaseProvider(context)
            val cacheDir = File(context.cacheDir, "video_cache")
            return SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES), databaseProvider)
                .also { cache = it }
        }
    }
}
