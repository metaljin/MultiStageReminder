package com.reminder.multistage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderService : android.app.Service() {
    companion object {
        const val ACTION_START = "START"
        const val EXTRA_ID = "ID"
        private const val CHANNEL = "REMINDER"
        private const val NOTIFY_ID = 100
    }

    private lateinit var db: AppDatabase
    private var data: TemplateWithStages? = null
    private var stageIndex = 0
    private var currentCycle = 0
    private var secondsLeft = 0
    private val handler = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            if (secondsLeft > 0) {
                secondsLeft--
                updateNotify()
                handler.postDelayed(this, 1000)
            } else {
                nextStage()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        createChannel()
        // 获取 WakeLock，防止屏幕关闭后 CPU 停止运行
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReminderApp::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        // 1. 立即显示通知，防止系统报错
        startForeground(NOTIFY_ID, createNotification("准备中..."))

        if (intent?.action == ACTION_START) {
            val tid = intent.getLongExtra(EXTRA_ID, -1)
            if (tid != -1L) {
                CoroutineScope(Dispatchers.IO).launch {
                    val result = db.reminderDao().getTemplateById(tid)
                    if (result != null && result.stages.isNotEmpty()) {
                        data = result
                        currentCycle = result.template.totalCycles
                        stageIndex = 0
                        // 回到主线程开始任务
                        handler.post { 
                            wakeLock?.acquire(30 * 60 * 1000L /* 30 min */)
                            startStage() 
                        }
                    } else {
                        stopServiceInternal()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startStage() {
        val templateData = data ?: return
        if (stageIndex >= templateData.stages.size) return

        val s = templateData.stages[stageIndex]
        secondsLeft = s.durationMinutes * 60
        
        handler.removeCallbacks(tick)
        handler.post(tick)
        updateNotify()
    }

    private fun nextStage() {
        val templateData = data ?: return
        val s = templateData.stages[stageIndex]

        // 播放铃声
        playRingtone(Uri.parse(s.ringtoneUri), s.maxPlaySeconds)

        // 逻辑计算：进入下一阶段或下一循环
        stageIndex++
        if (stageIndex >= templateData.stages.size) {
            currentCycle--
            if (currentCycle > 0) {
                stageIndex = 0
                startStage() // 继续下一轮循环
            } else {
                stopServiceInternal() // 全部结束
            }
        } else {
            startStage() // 继续本轮的下一个阶段
        }
    }

    private fun playRingtone(uri: Uri, maxSeconds: Int) {
        try {
            mp?.stop()
            mp?.release()
            mp = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                prepare()
                start()
            }
            // 仅停止音乐播放，不要停止整个 Service
            if (maxSeconds > 0) {
                handler.postDelayed({ 
                    mp?.stop()
                    mp?.release()
                    mp = null
                }, maxSeconds * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopServiceInternal() {
        handler.removeCallbacks(tick)
        mp?.stop()
        mp?.release()
        mp = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "提醒服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(data?.template?.name ?: "多阶段提醒")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true) // 更新通知时不震动/响铃
            .build()
    }

    private fun updateNotify() {
        val templateName = data?.template?.name ?: ""
        val info = "循环剩 ${currentCycle} 次 | 阶段 ${stageIndex + 1} | 剩余 ${secondsLeft}秒"
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, createNotification(info))
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopServiceInternal()
        super.onDestroy()
    }
}
