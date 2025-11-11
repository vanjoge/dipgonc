package com.remote.dipgonc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

object P2PManager {

    const val PORT: String = "8088";
    private const val PREFS_NAME = "P2PAppPrefs"
    private const val KEY_SECRET = "secret_key"
    private const val API_AUTH = "api_auth"
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var callBack: CallBack
    private val gonc = Gonc()
    private var status: P2PStatus = P2PStatus.DISCONNECTED
    private var nowSecretKey: String = ""

    abstract class CallBack {
        open fun onStatusChange(status: P2PStatus, msg: String) {}
    }

    // 初始化单例
    fun init(context: Context, callBack: CallBack) {
        if (isInitialized()) return
        this.callBack = callBack
        this.context = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 设置回调
        gonc.init(this.context)
        gonc.callBack = object : Gonc.CallBack() {
            override fun msg(info: String) {
                Log.d("Gonc", info)
            }

            override fun out(line: String) {
                Log.d("Gonc", line)
                if (line.contains("[socks5] Listening on 0.0.0.0:" + PORT)) {
                    setP2PStatus(P2PStatus.CONNECTED)
                } else if (line.contains("session closed")) {
                    setP2PStatus(P2PStatus.DISCONNECTED)
                } else if (line.contains("MQTT: Hello operation completed.")) {
                    setP2PStatus(P2PStatus.CONNECTING)
                } else if (line.contains("weak password detected")) {
                    setP2PStatus(P2PStatus.ERROR, "密钥强度过低")
                } else if (line.contains("no usable NAT types with peer")) {
                    setP2PStatus(P2PStatus.ERROR, "穿透失败")
                }
            }
        }
        start(getSecretKey())
    }


    // 保存密钥
    fun saveSecretKey(secretKey: String, auth: String) {
        prefs.edit() { putString(KEY_SECRET, secretKey).putString(API_AUTH, auth).apply() }
        if (secretKey != nowSecretKey) {
            start(secretKey)
        }
    }

    // 获取密钥
    fun getSecretKey(): String {
        return prefs.getString(KEY_SECRET, "") ?: ""
    }

    fun getAuth(): String {
        return prefs.getString(API_AUTH, "") ?: ""
    }

    private fun setP2PStatus(status: P2PStatus, msg: String = "") {
        if (status != this.status) {
            this.status = status
            callBack.onStatusChange(status, msg)
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

    // 开始P2P连接
    fun start(secretKey: String) {
        if (secretKey.isEmpty()) {
            setP2PStatus(P2PStatus.ERROR, "请先输入密钥")
            return
        }
        if (secretKey == nowSecretKey)
            return
        nowSecretKey = secretKey
        setP2PStatus(P2PStatus.CONNECTING)
        gonc.start(context, secretKey)
    }

    // 检查是否已初始化
    fun isInitialized(): Boolean {
        return ::context.isInitialized
    }
}