package com.remote.dipgonc

object VideoMetadataCache {
    private var lastKey: String = ""
    private var lastJson: String = ""

    @Synchronized
    fun put(key: String, json: String) {
        if (key.isBlank() || json.isBlank()) return
        lastKey = key
        lastJson = json
    }

    @Synchronized
    fun get(key: String): String? {
        return if (key == lastKey) lastJson.takeIf { it.isNotBlank() } else null
    }
}
