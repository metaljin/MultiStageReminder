package com.reminder.multistage

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// UI 数据模型保持不变
data class StageUIItem(
    val minutes: String = "1",
    val ringtoneUri: String = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString(),
    val ringtoneName: String = "默认铃声"
)

class EditTemplateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getInstance(applicationContext)
        val existingId = intent.getLongExtra("TEMPLATE_ID", -1L)

        setContent {
            MaterialTheme {
                EditTemplateScreen(
                    existingId = existingId,
                    db = db,
                    onExit = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTemplateScreen(existingId: Long, db: AppDatabase, onExit: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var cycles by remember { mutableStateOf("1") }
    
    // 全局状态控制
    var isVibrate by remember { mutableStateOf(true) }
    var isSound by remember { mutableStateOf(true) }
    var ringDuration by remember { mutableStateOf("10") }
    
    val stages = remember { mutableStateListOf(StageUIItem()) }
    
    // 初始化加载数据
    LaunchedEffect(existingId) {
        if (existingId != -1L) {
            withContext(Dispatchers.IO) {
                db.reminderDao().getTemplateById(existingId)?.let { data ->
                    name = data.template.name
                    cycles = data.template.totalCycles.toString()
                    isVibrate = data.template.isVibrateEnabled
                    isSound = data.template.isSoundEnabled
                    ringDuration = data.template.ringtoneDurationSeconds.toString()
                    
                    stages.clear()
                    stages.addAll(data.stages.map { 
                        StageUIItem(it.durationMinutes.toString(), it.ringtoneUri, it.ringtoneName) 
                    })
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (existingId == -1L) "添加提醒模板" else "编辑提醒模板") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth())
            
            Spacer(modifier = Modifier.height(12.dp))

            // 全局配置卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isVibrate, onCheckedChange = { isVibrate = it })
                        Text("开启震动", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(20.dp))
                        Checkbox(checked = isSound, onCheckedChange = { isSound = it })
                        Text("开启铃声", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ringDuration,
                        onValueChange = { ringDuration = it },
                        label = { Text("响铃/震动持续时间 (秒)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Text("阶段设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))

            // 列表部分
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(stages) { index, stage ->
                    // 将全局的 isSound 状态传给每一行，用于控制图标显示
                    StageItemRow(
                        index = index, 
                        stage = stage,
                        isGlobalSoundEnabled = isSound, // 传入全局开关状态
                        onDelete = { if (stages.size > 1) stages.removeAt(index) },
                        onUpdate = { updated -> stages[index] = updated }
                    )
                }
                item {
                    TextButton(
                        onClick = { stages.add(StageUIItem()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加新阶段")
                    }
                }
            }

            // 保存按钮
            Button(
                onClick = {
                    if (name.isBlank()) return@Button
                    (context as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
                        if (existingId != -1L) {
                            db.reminderDao().deleteTemplate(Template(id = existingId, name = "", totalCycles = 0)) 
                        }
                        val newId = db.reminderDao().insertTemplate(Template(
                            name = name, 
                            totalCycles = cycles.toIntOrNull() ?: 1,
                            isVibrateEnabled = isVibrate,
                            isSoundEnabled = isSound,
                            ringtoneDurationSeconds = ringDuration.toIntOrNull() ?: 10
                        ))
                        val entities = stages.mapIndexed { i, s ->
                            Stage(
                                templateId = newId, 
                                orderIndex = i, 
                                durationMinutes = s.minutes.toIntOrNull() ?: 1, 
                                ringtoneUri = s.ringtoneUri,
                                ringtoneName = s.ringtoneName // 确保 Stage 实体类也有这个字段
                            )
                        }
                        db.reminderDao().insertStages(entities)
                        withContext(Dispatchers.Main) { onExit() }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) { Text("保存并应用") }
        }
    }
}

@Composable
fun StageItemRow(
    index: Int, 
    stage: StageUIItem, 
    isGlobalSoundEnabled: Boolean, // 接收全局开关
    onDelete: () -> Unit, 
    onUpdate: (StageUIItem) -> Unit
) {
    val context = LocalContext.current
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(context, uri)
                val title = ringtone?.getTitle(context) ?: "未知铃声"
                
                // 文件持久化逻辑
                if (uri.toString().contains("content://media/external")) {
                    val internalPath = FileUtil.copyUriToInternalStorage(context, uri, title)
                    onUpdate(stage.copy(ringtoneUri = internalPath ?: uri.toString(), ringtoneName = title))
                } else {
                    onUpdate(stage.copy(ringtoneUri = uri.toString(), ringtoneName = title))
                }
            }
            // 如果 uri == null (用户选了静音)，我们保持原有的 ringtoneUri 不变，不执行更新
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("阶段 ${index + 1}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(45.dp))
            
            OutlinedTextField(
                value = stage.minutes,
                onValueChange = { onUpdate(stage.copy(minutes = it)) },
                label = { Text("分钟") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(85.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 铃声按钮：根据 isGlobalSoundEnabled 变换
            TextButton(
                onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择阶段铃声")
                        try {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(stage.ringtoneUri))
                        } catch (e: Exception) {}
                    }
                    launcher.launch(intent)
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(0.dp)
            ) {
                // 图标联动逻辑
                Icon(
                    imageVector = if (isGlobalSoundEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    // 如果全局静音，图标变灰色
                    tint = if (isGlobalSoundEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stage.ringtoneName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 如果全局静音，文字变灰色
                    color = if (isGlobalSoundEnabled) Color.Unspecified else Color.Gray
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}
