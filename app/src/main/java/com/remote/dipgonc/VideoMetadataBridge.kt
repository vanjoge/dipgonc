package com.remote.dipgonc

import android.webkit.JavascriptInterface

class VideoMetadataBridge {
    @JavascriptInterface
    fun cacheVideoInfo(key: String?, json: String?) {
        if (key.isNullOrBlank() || json.isNullOrBlank()) return
        VideoMetadataCache.put(key, json)
    }
}
