package com.remote.dipgonc

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object Rds {

    fun initgonc(context: Context, goncPath: String): Boolean {
        return try {
            val goncf = File(goncPath)
            if (goncf.exists() && goncf.canExecute()) {
                return true
            }

            copyAssetsFile(context, "gonc", goncPath)

            // 设置可执行权限
            goncf.setExecutable(true, false)
            goncf.exists() && goncf.canExecute()

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun copyAssetsFile(context: Context, fileName: String, output: String): Boolean {
        return try {
            context.assets.open(fileName).use { inputStream ->
                FileOutputStream(output).use { fos ->
                    inputStream.copyTo(fos)
                }
            }
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }
}