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
    private var cycles = 0
    private var seconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    private val tick = object : Runnable {
        override fun run() {
            if (seconds > 0) { seconds--; updateNotify(); handler.postDelayed(this, 1000) }
            else nextStage()
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(applicationContext)
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Reminder")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent?.action == ACTION_START) {
            val tid = intent.getLongExtra(EXTRA_ID, 0)
            CoroutineScope(Dispatchers.IO).launch {
                data = db.reminderDao().getTemplateById(tid)
                data?.let {
                    cycles = it.template.totalCycles
                    stageIndex = 0
                    startStage()
                    startForeground(NOTIFY_ID, notify())
                    wakeLock.acquire(10*60*1000L)
                }
            }
        }
        return START_STICKY
    }

    private fun startStage() {
        val s = data!!.stages[stageIndex]
        seconds = s.durationMinutes * 60
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun nextStage() {
        val s = data!!.stages[stageIndex]
        play(Uri.parse(s.ringtoneUri), s.maxPlaySeconds)
        stageIndex++
        if (stageIndex >= data!!.stages.size) {
            cycles--
            if (cycles > 0) stageIndex = 0 else stop()
        }
        startStage()
    }

    private fun play(uri: Uri, max: Int) {
        mp?.release()
        mp = MediaPlayer().apply {
            setDataSource(applicationContext, uri)
            prepare()
            start()
            if (max > 0) handler.postDelayed({ stop() }, max*1000L)
            setOnCompletionListener { release() }
        }
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        mp?.release()
        wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "提醒服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notify(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("运行中")
            .setContentText("阶段 ${stageIndex+1}")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotify() = getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notify())
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() = stop()
}
