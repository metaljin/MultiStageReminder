package com.reminder.multistage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        val db = AppDatabase.getInstance(applicationContext)

        setContent {
            MaterialTheme {
                MainScreen(
                    db = db,
                    onEdit = { templateId ->
                        val intent = Intent(this, EditTemplateActivity::class.java).apply {
                            putExtra("TEMPLATE_ID", templateId)
                        }
                        startActivity(intent)
                    },
                    onAdd = {
                        startActivity(Intent(this, EditTemplateActivity::class.java))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    db: AppDatabase,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit
) {
    val context = LocalContext.current
    val templates by db.reminderDao().getAllTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    // 关键：实时观察 Service 的 StateFlow
    val serviceState by ReminderService.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("多段提醒器") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        if (templates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无模板，请点击右下角添加")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(templates, key = { it.template.id }) { item ->
                    val isThisOneRunning = serviceState.isRunning && item.template.id == serviceState.templateId
                    
                    TemplateItemCard(
                        item = item,
                        isRunning = isThisOneRunning,
                        state = serviceState,
                        onStart = {
                            val intent = Intent(context, ReminderService::class.java).apply {
                                action = ReminderService.ACTION_START
                                putExtra(ReminderService.EXTRA_ID, item.template.id)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        },
                        onAction = { action ->
                            val intent = Intent(context, ReminderService::class.java).apply {
                                this.action = action
                            }
                            context.startService(intent)
                        },
                        onEdit = { onEdit(item.template.id) },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                db.reminderDao().deleteTemplate(item.template)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TemplateItemCard(
    item: TemplateWithStages,
    isRunning: Boolean,
    state: ServiceState,
    onStart: () -> Unit,
    onAction: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isRunning) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 第一行：标题和状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.template.name, 
                    style = MaterialTheme.typography.titleLarge, 
                    modifier = Modifier.weight(1f)
                )
                if (isRunning) {
                    Surface(
                        color = if (state.isPaused) Color.Gray else MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            if (state.isPaused) "暂停中" else "运行中", 
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 第二行：倒计时展示 或 基础信息
            if (isRunning) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = state.displayTime, 
                            style = MaterialTheme.typography.displayMedium, 
                            color = if (state.isPaused) Color.Gray else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = state.displayInfo, 
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = "配置：${item.template.totalCycles} 次循环 | ${item.stages.size} 个阶段",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 第三行：控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunning) {
                    // 左下角：暂停/恢复 和 跳过 按钮
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            onAction(if (state.isPaused) ReminderService.ACTION_RESUME else ReminderService.ACTION_PAUSE)
                        }) {
                            Icon(
                                imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "暂停/恢复",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        // 跳过按钮，如果不能跳过则置灰
                        IconButton(
                            onClick = { onAction(ReminderService.ACTION_SKIP) },
                            enabled = state.canSkip
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一阶段",
                                tint = if (state.canSkip) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }

                    // 右侧：停止按钮
                    Button(
                        onClick = { onAction(ReminderService.ACTION_STOP) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("强制停止")
                    }
                } else {
                    // 非运行状态的按钮
                    Row {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑")
                        }
                    }
                    Button(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("开始任务")
                    }
                }
            }
        }
    }
}