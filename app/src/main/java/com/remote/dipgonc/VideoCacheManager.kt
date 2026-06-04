package com.remote.dipgonc

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

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
