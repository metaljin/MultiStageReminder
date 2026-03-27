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
        var runningTemplateId = -1L 
        var displayTime = "00:00"    // 默认初始值
        var displayInfo = ""        
        
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
                    runningTemplateId = tid
                    startForeground(100, createNotification("准备启动..."))
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
                if (currentCycle < data.template.totalCycles) {
                    currentCycle++
                    currentStageIndex = 0
                } else {
                    displayTime = "完成"
                    displayInfo = "所有提醒已结束"
                    updateNotification("提醒任务已完成")
                    handler.postDelayed({ stopSelf() }, 3000)
                    return
                }
            }

            val stage = data.stages[currentStageIndex]
            val totalMillis = stage.durationMinutes * 60 * 1000L
            
            // 更新 UI 初始信息
            displayInfo = "循环 $currentCycle/${data.template.totalCycles} | 阶段 ${currentStageIndex + 1}/${data.stages.size}"

            countDownTimer?.cancel()
            countDownTimer = object : CountDownTimer(totalMillis, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val secTotal = millisUntilFinished / 1000
                    val m = secTotal / 60
                    val s = secTotal % 60
                    displayTime = String.format("%02d:%02d", m, s)
                    updateNotification("倒计时: $displayTime ($displayInfo)")
                }

                override fun onFinish() {
                    playRingtone(stage.ringtoneUri, stage.maxPlaySeconds)
                    currentStageIndex++
                    // 在进入下一步前稍微预设一下 UI
                    displayTime = "00:00" 
                    nextStep()
                }
            }.start()
        }

        nextStep()
    }

    private fun playRingtone(path: String, maxSeconds: Int) {
        try {
            stopMedia()
            mediaPlayer = MediaPlayer().apply {
                if (path.startsWith("/")) {
                    setDataSource(path)
                } else {
                    setDataSource(applicationContext, android.net.Uri.parse(path))
                }
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
        val stopPending = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "计时提醒", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("多阶段提醒: ${displayTime}") // 通知标题也显示时间，更直观
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止任务", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        runningTemplateId = -1L
        displayTime = "00:00"
        displayInfo = ""
        countDownTimer?.cancel()
        serviceScope.cancel()
        stopMedia()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
