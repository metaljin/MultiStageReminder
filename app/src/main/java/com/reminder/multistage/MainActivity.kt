package com.reminder.multistage

import android.Manifest
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
                        startService(intent)
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
    
    // 状态监听
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(templates, key = { it.template.id }) { item ->
                    val isThisOneRunning = serviceActive && item.template.id == activeId
                    TemplateItemCard(
                        item = item,
                        isRunning = isThisOneRunning,
                        timeText = currentTime,
                        infoText = currentInfo,
                        onStart = { onStartService(item.template.id) },
                        onStop = onStopService,
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
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "运行中", 
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 第二行：倒计时展示 或 基础信息
            if (isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = timeText, 
                        style = MaterialTheme.typography.displayMedium, 
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = infoText, 
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            } else {
                Text(
                    text = "配置：${item.template.totalCycles} 次循环 | ${item.stages.size} 个阶段",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 第三行：动作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (!isRunning) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("开始任务")
                    }
                } else {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("强制停止")
                    }
                }
            }
        }
    }
}
