package com.reminder.multistage

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.*
import androidx.core.app.NotificationCompat

class ReminderService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        var isRunning = false // 全局静态状态，供 UI 监听
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_ID = "ID"
        private const val CHANNEL_ID = "REMINDER_CH"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isRunning = true
                val tid = intent.getLongExtra(EXTRA_ID, -1L)
                startForeground(100, createNotification("任务运行中..."))
                // 这里执行你的多阶段倒计时逻辑...
                // 示例：触发播放 playRingtone(path, 10)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun playRingtone(path: String, maxSeconds: Int) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                prepare()
                start()
            }
            if (maxSeconds > 0) handler.postDelayed({ stopMedia() }, maxSeconds * 1000L)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopMedia() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, ReminderService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "提醒", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("多阶段提醒")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        stopMedia()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
