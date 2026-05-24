package com.remote.dipgonc

import android.content.Context
import android.graphics.Color
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException

class Gonc {

    abstract class CallBack {
        open fun onStart() {}
        open fun onStop() {}
        open fun msg(info: String) {}
        open fun out(info: String) {}
    }

    var callBack: CallBack? = null
    private var executableDir: File? = null
    private var isRunning = false
    private var process: Process? = null

    fun init(context: Context) {
        executableDir = context.applicationContext.filesDir
    }

    fun start(context: Context, code: String, tunnelMode: P2PManager.TunnelMode) {
        stop()
        try {
            while (isRunning) {
                Thread.sleep(100)
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        isRunning = true
        callBack?.onStart()

        Thread {
            try {
                run(context, code, tunnelMode)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            isRunning = false
            callBack?.onStop()
        }.start()
    }

    fun stop() {
        process?.let { p ->
            process = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                p.destroyForcibly()
            } else {
                p.destroy()
            }
        }
    }

    private fun run(context: Context, code: String, tunnelMode: P2PManager.TunnelMode) {
        val goncPath = executableDir!!.absolutePath + "/gonc_251"
        if (!Rds.initgonc(context, goncPath)) {
            msg("gonc初始化失败")
        } else {
            executeCommandAndDisplayOutput(goncPath, code, tunnelMode)
        }
    }

    private fun msg(info: String) {
        callBack?.msg(info)
    }

    private fun updateOutput(line: String, color: Int) {
        callBack?.out(line)
    }

    private fun executeCommandAndDisplayOutput(
        goncPath: String,
        passKey: String,
        tunnelMode: P2PManager.TunnelMode
    ) {
        var inputStream: InputStream? = null
        var reader: BufferedReader? = null
        var flag = true

        try {
            val processBuilder = ProcessBuilder(commandArgs(goncPath, passKey, tunnelMode))
            processBuilder.redirectErrorStream(true)
            process = processBuilder.start()

            inputStream = process!!.inputStream
            reader = BufferedReader(InputStreamReader(inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (flag) {
                    flag = false
                }
                updateOutput(line!!, Color.BLUE)
            }

            val exitCode = process!!.waitFor()
            msg("VRD已退出")
        } catch (e: InterruptedIOException) {
            // 保持原样
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } finally {
            try {
                reader?.close()
            } catch (_: IOException) {
            }
            try {
                inputStream?.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun commandArgs(
        goncPath: String,
        passKey: String,
        tunnelMode: P2PManager.TunnelMode
    ): List<String> {
        if (tunnelMode == P2PManager.TunnelMode.LEGACY) {
            return listOf(
                goncPath,
                "-p2p",
                passKey,
                "-socks5local-port",
                P2PManager.PORT,
                "-mqtt-hello"
            )
        }

        if (tunnelMode == P2PManager.TunnelMode.LINK) {
            return listOf(
                goncPath,
                "-p2p",
                passKey,
                "-ss",
                "-mqtt-hello",
                "-call",
                ":mux",
                "-link",
                P2PManager.PORT + ";none",
                "-keep-open"
            )
        }

        return listOf(
            goncPath,
            "-p2p",
            passKey,
            "-ss",
            "-mqtt-hello",
            "-call",
            ":" + tunnelMode.value,
            "-mux-l",
            P2PManager.DIP_HTTP_PORT,
            "-keep-open"
        )
    }
}
