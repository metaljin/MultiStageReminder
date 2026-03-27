package com.reminder.multistage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 权限申请
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }

        val db = AppDatabase.getInstance(applicationContext)

        setContent {
            MaterialTheme {
                MainScreen(
                    db = db,
                    onStartService = { templateId ->
                        val intent = Intent(this, ReminderService::class.java).apply {
                            action = ReminderService.ACTION_START
                            putExtra(ReminderService.EXTRA_ID, templateId)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                    },
                    onStopService = {
                        val intent = Intent(this, ReminderService::class.java).apply {
                            action = ReminderService.ACTION_STOP
                        }
                        startService(intent) // 发送停止指令
                    },
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
    onStartService: (Long) -> Unit,
    onStopService: () -> Unit,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit
) {
    val templates by db.reminderDao().getAllTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    
    // --- 核心修改：状态监听 ---
    var serviceActive by remember { mutableStateOf(ReminderService.isRunning) }
    var activeId by remember { mutableStateOf(ReminderService.runningTemplateId) }
    var currentTime by remember { mutableStateOf(ReminderService.displayTime) }
    var currentInfo by remember { mutableStateOf(ReminderService.displayInfo) }

    LaunchedEffect(Unit) {
        while(true) {
            serviceActive = ReminderService.isRunning
            activeId = ReminderService.runningTemplateId
            currentTime = ReminderService.displayTime
            currentInfo = ReminderService.displayInfo
            delay(500)
        }
    }
    // -----------------------

    Scaffold(
        topBar = { TopAppBar(title = { Text("多阶段提醒") }) },
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(templates) { item ->
                    val isThisOneRunning = serviceActive && item.template.id == activeId
                    TemplateItemCard(
                        item = item,
                        isRunning = isThisOneRunning,
                        timeText = currentTime,
                        infoText = currentInfo,
                        onStart = { onStartService(item.template.id) },
                        onStop = onStopService, // 传递停止动作
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
    timeText: String,
    infoText: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val template = item.template
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isRunning) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = item.template.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (isRunning) {
                    // 正在运行状态标签
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("运行中", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(text = "循环次数: ${template.totalCycles}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "阶段总数: ${item.stages.size}", style = MaterialTheme.typography.bodySmall)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("删除")
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text("编辑")
                }

                // --- 核心修改：动态切换按钮 ---
                if (isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = timeText, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = infoText, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(text = "循环: ${item.template.totalCycles} 次 | 阶段: ${item.stages.size}", style = MaterialTheme.typography.bodyMedium)
                }
                // ---------------------------
                Spacer(modifier = Modifier.height(16.dp))
            
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!isRunning) {
                        TextButton(onClick = onDelete) { Text("删除") }
                        TextButton(onClick = onEdit) { Text("编辑") }
                        Button(onClick = onStart) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("启动")
                        }
                    } else {
                        // 运行时只显示停止按钮，防止误操作
                        Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Default.Close, contentDescription = null) // 修复之前的 Stop 编译错误
                            Text("停止任务")
                        }
                    }
                }
            }
        }
    }
}
