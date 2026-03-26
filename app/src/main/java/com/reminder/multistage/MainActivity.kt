package com.reminder.multistage
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.reminder.multistage.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var bind: ActivityMainBinding
    private lateinit var db: AppDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityMainBinding.inflate(layoutInflater)
        setContentView(bind.root)
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.READ_MEDIA_AUDIO), 100)
        db = AppDatabase.getInstance(this)
        val adapter = TemplateAdapter({ start(it) }, {}, { delete(it) })
        bind.recyclerView.adapter = adapter
        bind.recyclerView.layoutManager = LinearLayoutManager(this)
        db.reminderDao().getAllTemplates().observe(this) { adapter.submitList(it) }
        bind.btnAdd.setOnClickListener { startActivity(Intent(this, EditTemplateActivity::class.java)) }
    }
    private fun start(id: Long) {
        val intent = Intent(this, ReminderService::class.java)
        intent.action = ReminderService.ACTION_START
        intent.putExtra(ReminderService.EXTRA_ID, id)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
    private fun delete(data: TemplateWithStages) {
        CoroutineScope(Dispatchers.IO).launch { db.reminderDao().deleteTemplate(data.template) }
    }
}
