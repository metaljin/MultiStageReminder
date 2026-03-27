package com.reminder.multistage

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtil {
    fun copyUriToInternalStorage(context: Context, uri: Uri, fileName: String): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val dir = File(context.filesDir, "ringtones").apply { if (!exists()) mkdirs() }
            // 加上时间戳防止重名
            val destFile = File(dir, "${System.currentTimeMillis()}_$fileName")
            
            inputStream?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath // 返回内部路径
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
