package com.reminder.multistage

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class ReminderService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var countDownTimer: CountDownTimer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        var isRunning = false
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_ID = "ID"
        private const val CHANNEL_ID = "REMINDER_CH"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tid = intent.getLongExtra(EXTRA_ID, -1L)
                if (tid != -1L && !isRunning) {
                    isRunning = true
                    startForeground(100, createNotification("正在初始化数据..."))
                    loadAndStart(tid)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun loadAndStart(templateId: Long) {
        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val data = withContext(Dispatchers.IO) {
                db.reminderDao().getTemplateById(templateId)
            }

            if (data != null && data.stages.isNotEmpty()) {
                startChain(data)
            } else {
                stopSelf()
            }
        }
    }

    private fun startChain(data: TemplateWithStages) {
        var currentCycle = 1
        var currentStageIndex = 0

        fun nextStep() {
            if (currentStageIndex >= data.stages.size) {
                // 当前循环的所有阶段跑完了
                if (currentCycle < data.template.totalCycles) {
                    currentCycle++
                    currentStageIndex = 0
                } else {
                    // 全部大循环结束
                    updateNotification("提醒任务已完成")
                    handler.postDelayed({ stopSelf() }, 3000)
                    return
                }
            }

            val stage = data.stages[currentStageIndex]
            val totalMillis = stage.durationMinutes * 60 * 1000L

            // 更新通知栏显示进度
            updateNotification("循环 $currentCycle/${data.template.totalCycles} | 阶段 ${currentStageIndex + 1}/${data.stages.size}")

            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(totalMillis, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val sec = millisUntilFinished / 1000
                    updateNotification("倒计时: ${sec / 60}:${String.format("%02d", sec % 60)} (循环 $currentCycle)")
                }

                override fun onFinish() {
                    playRingtone(stage.ringtoneUri, stage.maxPlaySeconds)
                    currentStageIndex++
                    nextStep() // 递归进入下一阶段
                }
            }.start()
        }

        nextStep()
    }

    private fun playRingtone(path: String, maxSeconds: Int) {
        try {
            stopMedia()
            mediaPlayer = MediaPlayer().apply {
                // 如果路径是 Uri 字符串(系统铃声)或绝对路径(私有目录)都能处理
                if (path.startsWith("/")) setDataSource(path) 
                else setDataSource(applicationContext, android.net.Uri.parse(path))

                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                prepare()
                start()
            }
            if (maxSeconds > 0) handler.postDelayed({ stopMedia() }, maxSeconds * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(100, createNotification(text))
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
            val channel = NotificationChannel(CHANNEL_ID, "计时提醒", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("多阶段提醒运行中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOnlyAlertOnce(true) // 重要：更新通知时不震动/响铃，只刷新文字
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止任务", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        countDownTimer?.cancel()
        serviceScope.cancel()
        stopMedia()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
