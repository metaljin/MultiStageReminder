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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
    val stages = remember { mutableStateListOf(StageUIItem()) }
    
    // 初始化数据（编辑模式）
    LaunchedEffect(existingId) {
        if (existingId != -1L) {
            withContext(Dispatchers.IO) {
                db.reminderDao().getTemplateById(existingId)?.let { data ->
                    name = data.template.name
                    cycles = data.template.totalCycles.toString()
                    stages.clear()
                    stages.addAll(data.stages.map { 
                        // 从路径中提取文件名作为显示名称
                        val displayName = if (it.ringtoneUri.contains("/")) {
                            it.ringtoneUri.substringAfterLast("/") 
                        } else {
                            "已保存铃声"
                        }
                        StageUIItem(it.durationMinutes.toString(), it.ringtoneUri, displayName) 
                    })
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (existingId == -1L) "添加模板" else "编辑模板") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("模板名称") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = cycles,
                onValueChange = { cycles = it },
                label = { Text("循环次数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Text("阶段列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(stages) { index, stage ->
                    StageItemRow(
                        index = index,
                        stage = stage,
                        onDelete = { if (stages.size > 1) stages.removeAt(index) },
                        onUpdate = { updated -> stages[index] = updated }
                    )
                }
                
                item {
                    TextButton(onClick = { stages.add(StageUIItem()) }) {
                        Text("+ 添加阶段")
                    }
                }
            }

            Button(
                onClick = {
                    if (name.isBlank()) {
                        Toast.makeText(context, "请输入名称", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    (context as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
                        // 如果是编辑模式，先删除旧的（为了简单处理多对多关系）
                        if (existingId != -1L) {
                            db.reminderDao().getTemplateById(existingId)?.let {
                                db.reminderDao().deleteTemplate(it.template)
                            }
                        }
                        
                        val newId = db.reminderDao().insertTemplate(Template(name = name, totalCycles = cycles.toIntOrNull() ?: 1))
                        val entities = stages.mapIndexed { i, s ->
                            Stage(
                                templateId = newId, 
                                orderIndex = i, 
                                durationMinutes = s.minutes.toIntOrNull() ?: 1, 
                                ringtoneUri = s.ringtoneUri, 
                                maxPlaySeconds = 10
                            )
                        }
                        db.reminderDao().insertStages(entities)
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                            onExit()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("保存模板")
            }
        }
    }
}

@Composable
fun StageItemRow(index: Int, stage: StageUIItem, onDelete: () -> Unit, onUpdate: (StageUIItem) -> Unit) {
    val context = LocalContext.current
    
    // 铃声选择回调
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            uri?.let { sourceUri ->
                // 1. 获取显示名称
                val ringtone = RingtoneManager.getRingtone(context, sourceUri)
                val title = ringtone.getTitle(context) ?: "未知铃声"
                
                // 2. 核心：复制文件到私有目录，确保永久访问权
                val internalPath = FileUtil.copyUriToInternalStorage(context, sourceUri, title)
                
                if (internalPath != null) {
                    onUpdate(stage.copy(ringtoneUri = internalPath, ringtoneName = title))
                } else {
                    // 如果复制失败（如系统铃声），退而求其次存 URI 字符串
                    onUpdate(stage.copy(ringtoneUri = sourceUri.toString(), ringtoneName = title))
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(65.dp)) {
                Text("阶段 ${index + 1}", style = MaterialTheme.typography.bodySmall)
            }
            
            OutlinedTextField(
                value = stage.minutes,
                onValueChange = { onUpdate(stage.copy(minutes = it)) },
                label = { Text("分钟") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(80.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 显示当前选择的铃声名称
            Column(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            // 注意：由于存储的是路径，预览可能不生效，这里暂传默认
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                        }
                        launcher.launch(intent)
                    },
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stage.ringtoneName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
