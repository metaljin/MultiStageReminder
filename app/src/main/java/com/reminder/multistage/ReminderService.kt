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

class ReminderService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var countDownTimer: CountDownTimer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        var isRunning = false
        var runningTemplateId = -1L 
        var displayTime = "00:00"
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
					val finalDelay = data.template.ringtoneDurationSeconds * 1000L
                    handler.postDelayed({ 
						if (isRunning) stopSelf() 
					}, finalDelay)
                    return
                }
            }

            val stage = data.stages[currentStageIndex]
            val totalMillis = stage.durationMinutes * 60 * 1000L
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
                    // 触发提醒：传入当前模板配置
                    playAlert(stage.ringtoneUri, data.template)
                    currentStageIndex++
                    displayTime = "00:00" 
                    nextStep()
                }
            }.start()
        }
        nextStep()
    }

    private fun playAlert(path: String, template: Template) {
        val durationMs = template.ringtoneDurationSeconds * 1000L

        // 1. 铃声逻辑
		if (template.isSoundEnabled && path.isNotEmpty()) {
			try {
				stopMedia()
				mediaPlayer = MediaPlayer().apply {
					val uri = android.net.Uri.parse(path)
                
					// 判断是否为内置资源路径
					if (path.startsWith("android.resource")) {
						val assetFileDescriptor = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")
						if (assetFileDescriptor != null) {
							setDataSource(assetFileDescriptor.fileDescriptor, assetFileDescriptor.startOffset, assetFileDescriptor.length)
							assetFileDescriptor.close()
						}
					} else {
						setDataSource(applicationContext, uri) // 系统选择器路径
					}

					setAudioAttributes(AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_ALARM)
						.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                
					isLooping = true
					prepare()
					start()
				}
			} catch (e: Exception) {
				e.printStackTrace()
            // 如果报错，尝试播放系统默认通知音作为保底
				playFallbackSound()
			}
		}

        // 2. 震动逻辑
		if (template.isVibrateEnabled) {
			try {
				vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
					vm.defaultVibrator
				} else {
					@Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
				}

				val pattern = longArrayOf(0, 800, 400) 
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					// 使用 VibrationEffect.DEFAULT_AMPLITUDE 确保兼容性
					vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
				} else {
					@Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0)
				}
			} catch (e: SecurityException) {
				// 如果没加权限，这里会捕获异常并打印，而不会闪退
				e.printStackTrace()
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}

		// 3. 达到设定时长后停止 (注意：如果逻辑重叠，这可能会取消下一阶段的提醒)
		handler.removeCallbacksAndMessages("STOP_TAG") // 清除之前的停止指令
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
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(100, createNotification(text))
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
            .setContentTitle("任务进行中: $displayTime")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        runningTemplateId = -1L
        countDownTimer?.cancel()
        serviceScope.cancel()
        stopMedia()
        stopVibration()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}