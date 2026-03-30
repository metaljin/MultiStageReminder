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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
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
    
    // 错误状态记录
    var isNameError by remember { mutableStateOf(false) }
    
    val stages = remember { mutableStateListOf(StageUIItem()) }
    
    // 初始化加载数据 (此处逻辑保持你原有的逻辑，但增加了新字段的读取)
    LaunchedEffect(existingId) {
        if (existingId != -1L) {
            withContext(Dispatchers.IO) {
                db.reminderDao().getTemplateById(existingId)?.let { data ->
                    name = data.template.name
                    cycles = data.template.totalCycles.toString()
                    // 假设你已在 Template 实体中增加了这些字段
                    isVibrate = data.template.isVibrateEnabled 
                    isSound = data.template.isSoundEnabled
                    
                    stages.clear()
                    stages.addAll(data.stages.map { 
                        StageUIItem(it.durationMinutes.toString(), it.ringtoneUri, it.ringtoneUri.substringAfterLast("/", "默认铃声")) 
                    })
                }
            }
        }
    }

    Scaffold(
        topBar = { 
			TopAppBar(
				title = { Text(if (existingId == -1L) "添加提醒模板" else "编辑提醒模板") },
				navigationIcon = {
					IconButton(onClick = onExit) {
						Icon(Icons.Default.ArrowBack, contentDescription = "返回")
					}
				}
			) 
		}
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            // 1. 模板标题区
            OutlinedTextField(
                value = name,
                onValueChange = { 
                    name = it
                    if (it.isNotBlank()) isNameError = false 
                },
                label = { Text("模板名称") },
                isError = isNameError,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    if (isNameError) Text("名称不能为空", color = MaterialTheme.colorScheme.error)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // 2. 全局配置卡片：通过颜色和阴影与背景区分
            Text("总体配置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isVibrate, onCheckedChange = { isVibrate = it })
                        Text("开启震动", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(20.dp))
                        Checkbox(checked = isSound, onCheckedChange = { isSound = it })
                        Text("开启铃声", style = MaterialTheme.typography.bodyMedium)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = ringDuration,
                            onValueChange = { ringDuration = it },
                            label = { Text("响铃时长(秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        OutlinedTextField(
                            value = cycles,
                            onValueChange = { cycles = it },
                            label = { Text("循环次数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. 阶段设置区
            Text("阶段设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(stages) { index, stage ->
                    StageItemRow(
                        index = index, 
                        stage = stage,
                        isGlobalSoundEnabled = isSound,
                        onDelete = { if (stages.size > 1) stages.removeAt(index) },
                        onUpdate = { updated -> stages[index] = updated }
                    )
                }
                item {
                    TextButton(
                        onClick = { stages.add(StageUIItem()) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("添加新阶段")
                    }
                }
            }

            // 4. 底部保存动作
            Button(
                onClick = {
                    if (name.isBlank()) {
                        isNameError = true
                        Toast.makeText(context, "请输入模板名称", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val finalCycles = cycles.toIntOrNull() ?: 1
                    val finalRingDuration = ringDuration.toIntOrNull() ?: 10

                    (context as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
                        if (existingId != -1L) {
                            db.reminderDao().deleteTemplate(Template(id = existingId, name = "", totalCycles = 0))
                        }

                        val newId = db.reminderDao().insertTemplate(
                            Template(
                                name = name,
                                totalCycles = finalCycles,
                                isVibrateEnabled = isVibrate,
                                isSoundEnabled = isSound,
                                ringtoneDurationSeconds = finalRingDuration
                            )
                        )

                        val entities = stages.mapIndexed { i, s ->
                            Stage(
                                templateId = newId,
                                orderIndex = i,
                                durationMinutes = s.minutes.toIntOrNull() ?: 1,
                                ringtoneUri = s.ringtoneUri,
                                ringtoneName = s.ringtoneName,
                                maxPlaySeconds = 0
                            )
                        }
                        db.reminderDao().insertStages(entities)

                        withContext(Dispatchers.Main) {
                            onExit()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { 
                Text("保存并应用", style = MaterialTheme.typography.titleMedium) 
            }
        }
    }
}

@Composable
fun StageItemRow(
    index: Int, 
    stage: StageUIItem, 
    isGlobalSoundEnabled: Boolean, 
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
                
                // 文件拷贝逻辑
                val internalPath = FileUtil.copyUriToInternalStorage(context, uri, title)
                onUpdate(stage.copy(ringtoneUri = internalPath ?: uri.toString(), ringtoneName = title))
            }
        }
    }

    // 使用 Surface 配合更大的 Elevation 实现悬浮效果
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp), // 增加外边距，让阴影更明显
        shape = MaterialTheme.shapes.large, // 使用大圆角
        color = MaterialTheme.colorScheme.surface, // 纯白背景
        tonalElevation = 4.dp, // 增加色调提升，产生更深的阴影感
        shadowElevation = 2.dp, // 物理阴影
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant) // 极细边框
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 阶段编号
            Text(
                text = "阶段 ${index + 1}", 
                style = MaterialTheme.typography.titleSmall, 
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.width(55.dp)
            )
            
            // 时长输入
            OutlinedTextField(
                value = stage.minutes,
                onValueChange = { onUpdate(stage.copy(minutes = it)) },
                label = { Text("分钟") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(90.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 铃声选择区
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
                contentPadding = PaddingValues(4.dp)
            ) {
                Icon(
                    imageVector = if (isGlobalSoundEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isGlobalSoundEnabled) MaterialTheme.colorScheme.secondary else Color.Gray
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stage.ringtoneName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isGlobalSoundEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
            }

            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete, 
                    contentDescription = "删除", 
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}
