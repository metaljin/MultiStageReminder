package com.reminder.multistage

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// 状态数据类
data class ServiceState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val displayTime: String = "00:00",
    val displayInfo: String = "",
    val canSkip: Boolean = true,
    val templateId: Long = -1L
)

class ReminderService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var countDownTimer: CountDownTimer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 内部运行状态
    private var currentData: TemplateWithStages? = null
    private var currentCycle = 1
    private var currentStageIndex = 0
    private var millisLeft: Long = 0

    companion object {
        private val _uiState = MutableStateFlow(ServiceState())
        val uiState = _uiState.asStateFlow()

        // 兼容旧属性（如果 MainActivity 还在引用，保留它们但让它们跟随 _uiState）
        val isRunning: Boolean get() = _uiState.value.isRunning
        val runningTemplateId: Long get() = _uiState.value.templateId
        val displayTime: String get() = _uiState.value.displayTime
        val displayInfo: String get() = _uiState.value.displayInfo

        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_SKIP = "SKIP"
        const val EXTRA_ID = "ID"
        private const val CHANNEL_ID = "REMINDER_CH"
        private const val NOTIFICATION_ID = 100
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val tid = intent.getLongExtra(EXTRA_ID, -1L)
                if (tid != -1L && !isRunning) {
                    loadAndStart(tid)
                }
            }
            ACTION_STOP -> stopSelf()
            ACTION_PAUSE -> pauseTimer()
            ACTION_RESUME -> resumeTimer()
            ACTION_SKIP -> skipStage()
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
                currentData = data
                currentCycle = 1
                currentStageIndex = 0
                _uiState.update { it.copy(isRunning = true, templateId = templateId) }
                startForeground(NOTIFICATION_ID, createNotification("准备启动..."))
                processNextStep()
            } else {
                stopSelf()
            }
        }
    }

    private fun processNextStep() {
        val data = currentData ?: return
        
        if (currentStageIndex >= data.stages.size) {
            if (currentCycle < data.template.totalCycles) {
                currentCycle++
                currentStageIndex = 0
            } else {
                // 全部结束
                _uiState.update { it.copy(displayTime = "完成", displayInfo = "所有提醒已结束", canSkip = false) }
                updateNotification("提醒任务已完成")
                
                val finalDelay = (data.template.ringtoneDurationSeconds + 1) * 1000L
                handler.postDelayed({ if (isRunning) stopSelf() }, finalDelay)
                return
            }
        }

        val stage = data.stages[currentStageIndex]
        val totalMillis = stage.durationMinutes * 60 * 1000L
        
        // 更新跳过按钮状态：如果这是最后一循环的最后一阶段，则不可跳过
        val canSkip = !(currentCycle == data.template.totalCycles && currentStageIndex == data.stages.size - 1)
        _uiState.update { it.copy(canSkip = canSkip) }

        startTimer(totalMillis)
    }

    private fun startTimer(duration: Long) {
        countDownTimer?.cancel()
        _uiState.update { it.copy(isPaused = false) }

        countDownTimer = object : CountDownTimer(duration, 1000) {
            override fun onTick(ms: Long) {
                millisLeft = ms
                val timeStr = formatTime(ms)
                val infoStr = "循环 $currentCycle/${currentData?.template?.totalCycles} | 阶段 ${currentStageIndex + 1}/${currentData?.stages?.size}"
                
                _uiState.update { it.copy(displayTime = timeStr, displayInfo = infoStr) }
                updateNotification("倒计时: $timeStr")
            }

            override fun onFinish() {
                currentData?.let { data ->
                    playAlert(data.stages[currentStageIndex].ringtoneUri, data.template)
                    currentStageIndex++
                    processNextStep()
                }
            }
        }.start()
    }

    private fun pauseTimer() {
        countDownTimer?.cancel()
        _uiState.update { it.copy(isPaused = true) }
        updateNotification("已暂停")
    }

    private fun resumeTimer() {
        if (_uiState.value.isPaused) {
            startTimer(millisLeft)
        }
    }

    private fun skipStage() {
        countDownTimer?.cancel()
        stopMedia()
        stopVibration()
        handler.removeCallbacksAndMessages("STOP_TAG")
        currentStageIndex++
        processNextStep()
    }

    private fun formatTime(ms: Long): String {
        val sec = ms / 1000
        return String.format("%02d:%02d", sec / 60, sec % 60)
    }

    // --- 提醒播放逻辑 (保持你的原有优化) ---
    private fun playAlert(path: String, template: Template) {
        val durationMs = template.ringtoneDurationSeconds * 1000L
        if (template.isSoundEnabled && path.isNotEmpty()) {
            try {
                stopMedia()
                mediaPlayer = MediaPlayer().apply {
                    val uri = android.net.Uri.parse(path)
                    if (path.startsWith("android.resource")) {
                        applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                            setDataSource(it.fileDescriptor, it.startOffset, it.length)
                        }
                    } else {
                        setDataSource(applicationContext, uri)
                    }
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                playFallbackSound()
            }
        }

        if (template.isVibrateEnabled) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 800, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
            }
        }

        handler.removeCallbacksAndMessages("STOP_TAG")
        handler.postAtTime({
            stopMedia()
            stopVibration()
        }, "STOP_TAG", SystemClock.uptimeMillis() + durationMs)
    }

    private fun playFallbackSound() {
        try {
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer.create(applicationContext, defaultUri)
            mediaPlayer?.start()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopMedia() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }

    // --- 通知栏逻辑 ---
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(content: String): Notification {
        val state = _uiState.value
        val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        // 意图定义
        val stopPending = PendingIntent.getService(this, 10, Intent(this, ReminderService::class.java).apply { action = ACTION_STOP }, flag)
        val skipPending = PendingIntent.getService(this, 11, Intent(this, ReminderService::class.java).apply { action = ACTION_SKIP }, flag)
        
        val pauseAction = if (state.isPaused) ACTION_RESUME else ACTION_PAUSE
        val pauseIcon = if (state.isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val pauseText = if (state.isPaused) "恢复" else "暂停"
        val pausePending = PendingIntent.getService(this, 12, Intent(this, ReminderService::class.java).apply { action = pauseAction }, flag)

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "计时提醒", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val title = if (state.isPaused) "已暂停: ${state.displayTime}" else "任务进行中: ${state.displayTime}"
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(pauseIcon, pauseText, pausePending)

        if (state.canSkip) {
            builder.addAction(android.R.drawable.ic_media_next, "跳过", skipPending)
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)

        return builder.build()
    }

    override fun onDestroy() {
        _uiState.value = ServiceState() // 重置状态
        countDownTimer?.cancel()
        serviceScope.cancel()
        stopMedia()
        stopVibration()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}