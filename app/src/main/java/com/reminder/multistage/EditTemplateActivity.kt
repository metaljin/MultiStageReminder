package com.reminder.multistage

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.reminder.multistage.databinding.ActivityEditTemplateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditTemplateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEditTemplateBinding
    private lateinit var db: AppDatabase
    
    // 默认铃声 URI（系统通知音）
    private var selectedRingtoneUri: String = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()

    // 注册铃声选择器回调
    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uri?.let {
                selectedRingtoneUri = it.toString()
                // 更新界面显示铃声名称（截取路径最后一段）
                binding.tvSelectedRingtone.text = "已选铃声: ${it.lastPathSegment ?: "自定义铃声"}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditTemplateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)

        // 绑定返回按钮（如果以后想加 Toolbar）
        initView()
    }

    private fun initView() {
        // 点击更换铃声
        binding.btnSelectRingtone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(selectedRingtoneUri))
            }
            ringtonePickerLauncher.launch(intent)
        }

        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveTemplate()
        }
    }

    private fun saveTemplate() {
        val name = binding.etName.text.toString().trim()
        val cyclesStr = binding.etCycles.text.toString().trim()
        val cycles = cyclesStr.toIntOrNull() ?: 1

        if (name.isEmpty()) {
            binding.tilName.error = "名称不能为空"
            return
        } else {
            binding.tilName.error = null
        }

        // 使用协程异步保存数据
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 1. 创建并插入模板
                    val template = Template(name = name, totalCycles = cycles)
                    val templateId = db.reminderDao().insertTemplate(template)

                    // 2. 为该模板创建一个默认阶段（1分钟后提醒）
                    // 这样主页点击“启动”时，Service 才有数据可以播放
                    val defaultStage = Stage(
                        templateId = templateId,
                        orderIndex = 0,
                        durationMinutes = 1,
                        ringtoneUri = selectedRingtoneUri,
                        maxPlaySeconds = 10
                    )
                    db.reminderDao().insertStages(listOf(defaultStage))
                }
                
                Toast.makeText(this@EditTemplateActivity, "模板已保存", Toast.LENGTH_SHORT).show()
                finish() // 返回主页
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@EditTemplateActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
