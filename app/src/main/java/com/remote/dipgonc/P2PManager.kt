package com.remote.dipgonc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat

object P2PManager {

    const val DIP_HTTP_PORT: String = "8988"
    const val PORT: String = "8088"
    private const val PREFS_NAME = "P2PAppPrefs"
    private const val KEY_SECRET = "secret_key"
    private const val API_AUTH = "api_auth"
    private const val KEY_TUNNEL_MODE = "tunnel_mode"
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var callBack: CallBack
    private val gonc = Gonc()
    private var status: P2PStatus = P2PStatus.DISCONNECTED
    private var nowSecretKey: String = ""
    private var nowTunnelMode: TunnelMode = TunnelMode.LEGACY
    @Volatile
    private var lanWebUrl: String? = null

    abstract class CallBack {
        open fun onStatusChange(status: P2PStatus, msg: String) {}
    }

    // 初始化单例
    fun init(context: Context, callBack: CallBack) {
        this.callBack = callBack
        if (isInitialized()) {
            callBack.onStatusChange(status, "")
            return
        }
        this.context = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        nowTunnelMode = getTunnelMode()
        // 设置回调
        gonc.init(this.context)
        gonc.callBack = object : Gonc.CallBack() {
            override fun msg(info: String) {
                Log.d("Gonc", info)
            }

            override fun out(line: String) {
                Log.d("Gonc", line)
                if (isConnectedLine(line)) {
                    setP2PStatus(P2PStatus.CONNECTED)
                } else if (line.contains("session closed")
                    ||line.contains("Disconnected from")) {
                    setP2PStatus(P2PStatus.DISCONNECTED)
                } else if (
                    line.contains("Hello operation completed.")
                    && status != P2PStatus.CONNECTED) {
                    setP2PStatus(P2PStatus.CONNECTING)
                } else if (line.contains("weak password detected")) {
                    setP2PStatus(P2PStatus.ERROR, "密钥强度过低")
                } else if (line.contains("no usable NAT types with peer")) {
                    setP2PStatus(P2PStatus.ERROR, "穿透失败")
                }
            }

            override fun onExit(stopRequested: Boolean) {
                if (!stopRequested && (status == P2PStatus.CONNECTING || status == P2PStatus.CONNECTED)) {
                    setP2PStatus(P2PStatus.DISCONNECTED)
                    stopKeepAliveService()
                }
            }
        }
        start(getSecretKey())
    }

    private fun isConnectedLine(line: String): Boolean {
        return when (getTunnelMode()) {
            TunnelMode.LEGACY,TunnelMode.LINK  ->
                line.contains("Listening on 0.0.0.0:" + PORT)
//                    line.contains("Listening", ignoreCase = true) &&
//                line.contains(PORT)
            TunnelMode.NC -> line.contains("Mux client ready", ignoreCase = true) &&
                line.contains(":$DIP_HTTP_PORT")
        }
    }


    // 保存密钥
    fun saveSecretKey(secretKey: String, auth: String) {
        saveSettings(secretKey, auth, getTunnelMode())
    }

    fun saveSettings(secretKey: String, auth: String, tunnelMode: TunnelMode) {
        val modeChanged = tunnelMode != getTunnelMode()
        prefs.edit().putString(KEY_SECRET, secretKey)
            .putString(API_AUTH, auth)
            .putString(KEY_TUNNEL_MODE, tunnelMode.value)
            .apply()
        if (secretKey != nowSecretKey || modeChanged) {
            start(secretKey, force = modeChanged)
        }
    }

    // 获取密钥
    fun getSecretKey(): String {
        return prefs.getString(KEY_SECRET, "") ?: ""
    }

    fun getAuth(): String {
        return prefs.getString(API_AUTH, "") ?: ""
    }

    fun getTunnelMode(): TunnelMode {
        if (!::prefs.isInitialized) return TunnelMode.LEGACY
        return TunnelMode.fromValue(prefs.getString(KEY_TUNNEL_MODE, null))
    }

    fun getWebUrl(): String {
        lanWebUrl?.let { return it }
        return when (getTunnelMode()) {
            TunnelMode.LEGACY -> "http://127.0.0.1-$DIP_HTTP_PORT.gonc.cc:$PORT"
            TunnelMode.LINK -> "http://127.0.0.1-$DIP_HTTP_PORT.gonc.cc:$PORT"
            TunnelMode.NC -> "http://127.0.0.1:$DIP_HTTP_PORT"
        }
    }

    fun getListenPort(): String {
        return when (getTunnelMode()) {
            TunnelMode.LEGACY -> PORT
            TunnelMode.LINK -> PORT
            TunnelMode.NC -> DIP_HTTP_PORT
        }
    }

    private fun setP2PStatus(status: P2PStatus, msg: String = "") {
        if (status != this.status) {
            this.status = status
            if (::callBack.isInitialized) {
                callBack.onStatusChange(status, msg)
            }
            GoncForegroundService.updateNotification(context, status)
        }
    }

    // 获取P2P状态
    fun getP2PStatus(): P2PStatus {
        return status
    }

    // P2P状态枚举
    enum class P2PStatus {
        CONNECTING, CONNECTED, DISCONNECTED, ERROR
    }

    enum class TunnelMode(val value: String) {
        NC("nc"),
        LINK("link"),
        LEGACY("legacy");

        companion object {
            fun fromValue(value: String?): TunnelMode {
                return entries.firstOrNull { it.value == value } ?: LEGACY
            }
        }
    }

    // 开始P2P连接
    fun start(secretKey: String, force: Boolean = false) {
        val tunnelMode = getTunnelMode()
        if (!force &&
            secretKey == nowSecretKey &&
            tunnelMode == nowTunnelMode &&
            (status == P2PStatus.CONNECTING || status == P2PStatus.CONNECTED)
        )
            return
        nowSecretKey = secretKey
        nowTunnelMode = tunnelMode
        lanWebUrl = null
        setP2PStatus(P2PStatus.CONNECTING)
        gonc.stop()
        startKeepAliveService()

        Thread {
            val lanHost = LanDiscovery.findHttpHost(DIP_HTTP_PORT.toInt())
            if (lanHost != null) {
                lanWebUrl = "http://$lanHost:$DIP_HTTP_PORT"
                setP2PStatus(P2PStatus.CONNECTED)
                return@Thread
            }

            if (secretKey.isEmpty()) {
                setP2PStatus(P2PStatus.ERROR, "请先输入密钥")
                return@Thread
            }
            gonc.start(context, secretKey, tunnelMode)
        }.start()
    }

    fun stopByUser() {
        lanWebUrl = null
        gonc.stop()
        setP2PStatus(P2PStatus.DISCONNECTED)
        stopKeepAliveService()
    }

    private fun startKeepAliveService() {
        if (!::context.isInitialized) return
        val intent = GoncForegroundService.startIntent(context)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopKeepAliveService() {
        if (!::context.isInitialized) return
        context.stopService(GoncForegroundService.intent(context))
    }

    // 检查是否已初始化
    fun isInitialized(): Boolean {
        return ::context.isInitialized
    }
}
