package com.shizuku.speedtest.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shizuku.speedtest.AppViewModel
import com.shizuku.speedtest.data.KnownNetwork
import com.shizuku.speedtest.data.SettingsStore
import com.shizuku.speedtest.data.SpeedNode
import com.shizuku.speedtest.data.ThemeMode
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val nodes by viewModel.nodes.collectAsState()
    val networks by viewModel.networks.collectAsState()
    val retentionDays by viewModel.retentionDays.collectAsState()
    val resultCount by viewModel.resultCount.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingNode by remember { mutableStateOf<SpeedNode?>(null) }
    var addingNode by remember { mutableStateOf(false) }
    var editingNetwork by remember { mutableStateOf<KnownNetwork?>(null) }
    var showCustomRetention by remember { mutableStateOf(false) }
    var confirmDeleteNode by remember { mutableStateOf<SpeedNode?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = viewModel.buildCsv()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(csv.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = viewModel.buildBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                viewModel.showMessage("备份已保存")
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalArgumentException("读不到备份文件")
                    val summary = viewModel.restoreBackupJson(json)
                    val skipNote = if (summary.skipped > 0) "（${summary.skipped} 条已存在，跳过）" else ""
                    viewModel.showMessage(
                        "已恢复：${summary.results} 条记录，${summary.networks} 个网络，${summary.nodes} 个节点$skipNote"
                    )
                } catch (e: Exception) {
                    viewModel.showMessage("恢复失败：${e.message ?: "文件有问题"}")
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            Text("外观", style = MaterialTheme.typography.titleMedium)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ThemeMode.values().forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setThemeMode(mode) }
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) }
                            )
                            Text(mode.label)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("动态取色", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    "跟随壁纸颜色；关掉后用固定的藏青配色"
                                } else {
                                    "需要 Android 12 及以上，当前系统不支持"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { viewModel.setDynamicColor(it) },
                            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "测速节点",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FloatingActionButton(
                    onClick = { addingNode = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加节点")
                }
            }
            Text(
                "自定义节点支持三种格式：Cloudflare 兼容（__down / __up）、LibreSpeed（填测速网站首页地址，如 https://speedtest4.cc.shu.edu.cn）或本地服务（/down、/up、/ping，电脑上跑配套脚本）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(nodes, key = { "node-${it.id}" }) { node ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(node.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            node.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            node.protocolLabel + if (node.builtin) " · 内置" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { editingNode = node }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    if (!node.builtin) {
                        IconButton(onClick = { confirmDeleteNode = node }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }

        item {
            Text(
                "网络",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "给每个网络起个别名，还能指定它默认用的测速节点（比如家里 WiFi 默认测本地）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (networks.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "还没有记录到网络，测一次速就会出现在这里。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        items(networks, key = { "network-${it.id}" }) { network ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingNetwork = network }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(network.displayName, style = MaterialTheme.typography.bodyMedium)
                        if (!network.isMobile()) {
                            Text(
                                network.ssid + if (network.bssid.isNotEmpty()) " · ${network.bssid}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val defaultNodeName = nodes.find { it.id == network.defaultNodeId }?.name ?: "跟随上次选择"
                        Text(
                            "默认节点：$defaultNodeName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.Edit, contentDescription = null)
                }
            }
        }

        item {
            Text(
                "数据",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("历史保存时长", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "过期记录会在打开 App 和每次测速后自动清理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsStore.RETENTION_OPTIONS.forEach { days ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setRetentionDays(days) }
                        ) {
                            RadioButton(
                                selected = retentionDays == days,
                                onClick = { viewModel.setRetentionDays(days) }
                            )
                            Text(SettingsStore.retentionLabel(days))
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomRetention = true }
                    ) {
                        RadioButton(
                            selected = retentionDays !in SettingsStore.RETENTION_OPTIONS,
                            onClick = { showCustomRetention = true }
                        )
                        Text(
                            if (retentionDays !in SettingsStore.RETENTION_OPTIONS) {
                                "自定义：${SettingsStore.retentionLabel(retentionDays)}"
                            } else {
                                "自定义天数…"
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("已保存 $resultCount 条测速记录", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                                exportLauncher.launch("测速记录_$stamp.csv")
                            },
                            enabled = resultCount > 0
                        ) {
                            Text("导出 CSV")
                        }
                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
                                backupLauncher.launch("测速备份_$stamp.json")
                            }
                        ) {
                            Text("备份数据")
                        }
                        OutlinedButton(
                            onClick = {
                                restoreLauncher.launch(arrayOf("application/json"))
                            }
                        ) {
                            Text("恢复数据")
                        }
                    }
                    Text(
                        "备份会保存全部网络、节点和历史记录；换手机或重装后用“恢复数据”导回来，重复的记录会自动跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shizuku测速 v1.7\n所有测速记录只保存在本机，不上传。测速节点可自行配置；测本地 WiFi 速度时，在电脑上运行配套的 local_speed_server.py，再在上面添加一个本地服务节点即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    if (addingNode) {
        NodeEditDialog(
            node = null,
            viewModel = viewModel,
            onDismiss = { addingNode = false },
            onConfirm = { name, url, protocol ->
                viewModel.addNode(name, url, protocol)
                addingNode = false
            }
        )
    }

    editingNode?.let { node ->
        NodeEditDialog(
            node = node,
            viewModel = viewModel,
            onDismiss = { editingNode = null },
            onConfirm = { name, url, protocol ->
                viewModel.updateNode(node.copy(name = name, baseUrl = url, protocol = protocol))
                editingNode = null
            }
        )
    }

    confirmDeleteNode?.let { node ->
        AlertDialog(
            onDismissRequest = { confirmDeleteNode = null },
            title = { Text("删除节点") },
            text = { Text("确定删除「${node.name}」吗？已保存的测速记录不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteNode(node.id)
                        confirmDeleteNode = null
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteNode = null }) {
                    Text("取消")
                }
            }
        )
    }

    editingNetwork?.let { network ->
        NetworkEditDialog(
            network = network,
            nodes = nodes,
            onDismiss = { editingNetwork = null },
            onConfirm = { alias, defaultNodeId ->
                viewModel.setNetworkAlias(network.id, alias)
                viewModel.setNetworkDefaultNode(network.id, defaultNodeId)
                editingNetwork = null
            }
        )
    }

    if (showCustomRetention) {
        CustomRetentionDialog(
            current = retentionDays,
            onDismiss = { showCustomRetention = false },
            onConfirm = { days ->
                viewModel.setRetentionDays(days)
                showCustomRetention = false
            }
        )
    }
}

@Composable
private fun NodeEditDialog(
    node: SpeedNode?,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, protocol: String) -> Unit
) {
    var name by remember { mutableStateOf(node?.name ?: "") }
    var url by remember { mutableStateOf(node?.baseUrl ?: "") }
    var protocol by remember { mutableStateOf(node?.protocol ?: SpeedNode.PROTOCOL_CLOUDFLARE) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isBuiltin = node?.builtin == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (node == null) "添加节点" else "编辑节点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("地址，如 https://speed.cloudflare.com") },
                    singleLine = true,
                    enabled = !isBuiltin,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("类型", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = protocol == SpeedNode.PROTOCOL_CLOUDFLARE,
                        onClick = { protocol = SpeedNode.PROTOCOL_CLOUDFLARE },
                        enabled = !isBuiltin
                    )
                    Text(
                        "Cloudflare 兼容",
                        modifier = Modifier.clickable(enabled = !isBuiltin) {
                            protocol = SpeedNode.PROTOCOL_CLOUDFLARE
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = protocol == SpeedNode.PROTOCOL_LIBRESPEED || protocol == SpeedNode.PROTOCOL_LIBRESPEED_ROOT,
                        onClick = { protocol = SpeedNode.PROTOCOL_LIBRESPEED },
                        enabled = !isBuiltin
                    )
                    Text(
                        "LibreSpeed",
                        modifier = Modifier.clickable(enabled = !isBuiltin) {
                            protocol = SpeedNode.PROTOCOL_LIBRESPEED
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = protocol == SpeedNode.PROTOCOL_LOCAL,
                        onClick = { protocol = SpeedNode.PROTOCOL_LOCAL },
                        enabled = !isBuiltin
                    )
                    Text(
                        "本地服务",
                        modifier = Modifier.clickable(enabled = !isBuiltin) {
                            protocol = SpeedNode.PROTOCOL_LOCAL
                        }
                    )
                }
                if (protocol == SpeedNode.PROTOCOL_LIBRESPEED || protocol == SpeedNode.PROTOCOL_LIBRESPEED_ROOT) {
                    Text(
                        "LibreSpeed 地址填测速网站的首页地址，如 https://speedtest4.cc.shu.edu.cn，App 会自动用它的测速接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (protocol == SpeedNode.PROTOCOL_LOCAL) {
                    Text(
                        "本地服务地址填电脑的局域网地址，如 http://192.168.6.100:8899，电脑上要先跑起配套脚本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            val trimmed = url.trim().trimEnd('/')
                            if (trimmed.isEmpty()) {
                                probeResult = "请先填写地址"
                                return@OutlinedButton
                            }
                            probing = true
                            probeResult = null
                            scope.launch {
                                val ms = viewModel.probeNode(
                                    SpeedNode(name = name, baseUrl = trimmed, protocol = protocol)
                                )
                                probing = false
                                probeResult = if (ms != null) {
                                    "连通，延迟 ${formatMs(ms)} ms"
                                } else {
                                    "连不通，请检查地址和网络"
                                }
                            }
                        },
                        enabled = !probing
                    ) {
                        Text(if (probing) "测试中…" else "测试连通性")
                    }
                    probeResult?.let { msg ->
                        Spacer(Modifier.width(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedUrl = url.trim().trimEnd('/')
                    if (name.isNotBlank() && trimmedUrl.isNotBlank()) {
                        onConfirm(name.trim(), trimmedUrl, protocol)
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun NetworkEditDialog(
    network: KnownNetwork,
    nodes: List<SpeedNode>,
    onDismiss: () -> Unit,
    onConfirm: (alias: String, defaultNodeId: Long?) -> Unit
) {
    var alias by remember { mutableStateOf(network.alias) }
    var nodeId by remember { mutableStateOf(network.defaultNodeId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑网络") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (network.isMobile()) "移动数据" else network.ssid,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("别名，如：客厅 H3C") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("默认测速节点", style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nodeId = null }
                ) {
                    RadioButton(selected = nodeId == null, onClick = { nodeId = null })
                    Text("跟随上次选择")
                }
                nodes.forEach { n ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nodeId = n.id }
                    ) {
                        RadioButton(selected = nodeId == n.id, onClick = { nodeId = n.id })
                        Text(n.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alias.trim(), nodeId) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CustomRetentionDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(if (current > 0) current.toString() else "60") }
    val days = text.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义保存天数") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text("天数") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { days?.let(onConfirm) },
                enabled = days != null && days > 0
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
