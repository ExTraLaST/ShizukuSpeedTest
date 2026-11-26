package com.shizuku.speedtest.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shizuku.speedtest.AppViewModel
import com.shizuku.speedtest.TestUiState
import com.shizuku.speedtest.data.TestResult
import com.shizuku.speedtest.data.TrafficTier
import com.shizuku.speedtest.net.TestPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit,
) {
    val info by viewModel.currentNetworkInfo.collectAsState()
    val network by viewModel.currentNetwork.collectAsState()
    val nodes by viewModel.nodes.collectAsState()
    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
    val tier by viewModel.tier.collectAsState()
    val testState by viewModel.testState.collectAsState()
    val recentResults by viewModel.recentResults.collectAsState()
    val wifiPermission by viewModel.wifiPermissionState.collectAsState()
    val context = LocalContext.current

    val selectedNode = nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.firstOrNull()
    val isRunning = testState is TestUiState.Running
    var nodeMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (info == null || info!!.isWifi) {
                            Icons.Default.Wifi
                        } else {
                            Icons.Default.SignalCellularAlt
                        },
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = network?.displayName ?: "未连接",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        if (info != null && info!!.isWifi) {
                            Text(
                                text = "信号 ${info!!.rssi} dBm · ${info!!.band} · 协商 ${info!!.linkSpeedMbps} Mbps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            info!!.gatewayIp?.let { gw ->
                                val bssidPart = info!!.bssid
                                    .takeIf { it.contains(":") }
                                    ?.let { " · $it" }
                                    ?: ""
                                Text(
                                    text = "网关 $gw$bssidPart",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        } else if (info != null) {
                            Text(
                                text = "移动数据",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.refreshWifi() },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("刷新")
                    }
                }
            }
        }

        if (info != null && info!!.isWifi && !info!!.ssidKnown) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (!wifiPermission.permissionGranted) {
                            Text(
                                text = "未授予位置权限，只能按网关区分网络",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "授予后才能读取 WiFi 名称和路由器 MAC，把不同路由器分开记录。权限只用于识别网络，不会上传任何数据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onRequestPermission) {
                                Text("授予权限")
                            }
                        } else if (!wifiPermission.locationEnabled) {
                            Text(
                                text = "定位服务未开启，仍读不到 WiFi 名称",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "权限已经给了，但系统定位总开关没开，Android 仍不返回 WiFi 名称。打开定位服务后回到 App 即可，不用重启。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                },
                            ) {
                                Text("去开启定位服务")
                            }
                        } else {
                            Text(
                                text = "暂时读不到 WiFi 名称",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "权限和定位服务都正常，可能是系统还没更新 WiFi 信息。点上面卡片的“刷新”重试，或重新连接一次 WiFi。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { viewModel.refreshWifi() }) {
                                Text("重新读取")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val unit = when (val s = testState) {
                        is TestUiState.Running -> if (s.phase == TestPhase.LATENCY) "ms" else "Mbps"
                        else -> "Mbps"
                    }
                    val (gaugeValue, gaugeLabel) = when (val s = testState) {
                        is TestUiState.Running -> s.liveValue to when (s.phase) {
                            TestPhase.LATENCY -> "正在测延迟…"
                            TestPhase.DOWNLOAD -> "下载中"
                            TestPhase.UPLOAD -> "上传中"
                        }
                        is TestUiState.Done -> s.outcome.downloadMbps to "下载速度"
                        else -> 0.0 to "就绪"
                    }

                    when (val s = testState) {
                        is TestUiState.Running -> {
                            PhaseIndicator(currentPhase = s.phase, allDone = false)
                            Spacer(Modifier.height(12.dp))
                        }
                        is TestUiState.Done -> {
                            PhaseIndicator(currentPhase = null, allDone = true)
                            Spacer(Modifier.height(12.dp))
                        }
                        else -> {}
                    }

                    SpeedGauge(value = gaugeValue, unit = unit, label = gaugeLabel)

                    when (val s = testState) {
                        is TestUiState.Running -> {
                            if (s.phaseSamples.size > 1) {
                                Spacer(Modifier.height(8.dp))
                                val sparkColor = when (s.phase) {
                                    TestPhase.LATENCY -> MaterialTheme.colorScheme.secondary
                                    TestPhase.DOWNLOAD -> MaterialTheme.colorScheme.primary
                                    TestPhase.UPLOAD -> MaterialTheme.colorScheme.tertiary
                                }
                                SpeedSparkline(
                                    samples = s.phaseSamples,
                                    color = sparkColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                )
                            }
                        }
                        is TestUiState.Done -> {
                            Spacer(Modifier.height(8.dp))
                            DualSparkline(
                                downloadSamples = s.outcome.downloadSamples,
                                uploadSamples = s.outcome.uploadSamples,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                            )
                        }
                        else -> {}
                    }

                    Spacer(Modifier.height(12.dp))

                    if (isRunning) {
                        OutlinedButton(
                            onClick = { viewModel.cancelTest() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("取消测速")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startTest() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = info != null && selectedNode != null,
                        ) {
                            Text("开始测速")
                        }
                    }

                    if (info == null) {
                        Text(
                            text = "当前无网络连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (testState is TestUiState.Done) {
            val outcome = (testState as TestUiState.Done).outcome
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResultCard(
                            title = "下载",
                            value = formatSpeed(outcome.downloadMbps),
                            unit = "Mbps",
                            icon = Icons.Default.Download,
                            modifier = Modifier.weight(1f),
                        )
                        ResultCard(
                            title = "上传",
                            value = formatSpeed(outcome.uploadMbps),
                            unit = "Mbps",
                            icon = Icons.Default.Upload,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResultCard(
                            title = "延迟",
                            value = formatMs(outcome.latencyMs),
                            unit = "ms",
                            icon = Icons.Default.Timer,
                            modifier = Modifier.weight(1f),
                        )
                        ResultCard(
                            title = "抖动",
                            value = formatMs(outcome.jitterMs),
                            unit = "ms",
                            icon = Icons.Default.Timer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResultCard(
                            title = "丢包",
                            value = String.format(Locale.US, "%.1f%%", outcome.packetLoss * 100),
                            unit = "",
                            icon = Icons.Default.SignalCellularAlt,
                            modifier = Modifier.weight(1f),
                        )
                        ResultCard(
                            title = "网关延迟",
                            value = outcome.gatewayLatencyMs?.let { formatMs(it) } ?: "—",
                            unit = if (outcome.gatewayLatencyMs == null) "" else "ms",
                            icon = Icons.Default.Wifi,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (testState is TestUiState.Error) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = "测速失败：${(testState as TestUiState.Error).message}",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nodeMenuExpanded = true },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "测速节点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = selectedNode?.name ?: "无可用节点",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (selectedNode != null) {
                                Text(
                                    text = "${selectedNode.protocolLabel} · ${selectedNode.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DropdownMenu(
                    expanded = nodeMenuExpanded,
                    onDismissRequest = { nodeMenuExpanded = false },
                ) {
                    nodes.forEach { node ->
                        DropdownMenuItem(
                            text = { Text("${node.name}（${node.protocolLabel}）") },
                            onClick = {
                                viewModel.selectNode(node.id)
                                nodeMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }

        item {
            Column {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    TrafficTier.values().forEachIndexed { idx, t ->
                        SegmentedButton(
                            selected = tier == t,
                            onClick = { viewModel.setTier(t) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = idx,
                                count = TrafficTier.values().size,
                            ),
                        ) {
                            Text(t.label)
                        }
                    }
                }
                Text(
                    text = "下载 / 上传各持续 ${tier.durationSec} 秒 · ${tier.concurrency} 路并发（流量随实际网速变化）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (recentResults.isNotEmpty()) {
            item {
                Text(
                    text = "最近记录（当前网络）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(recentResults, key = { it.id }) { result ->
                RecentResultRow(result)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PhaseIndicator(currentPhase: TestPhase?, allDone: Boolean) {
    val phases = listOf(
        TestPhase.LATENCY to "延迟",
        TestPhase.DOWNLOAD to "下载",
        TestPhase.UPLOAD to "上传",
    )
    val activeIdx = when {
        allDone -> phases.size
        currentPhase == null -> -1
        else -> phases.indexOfFirst { it.first == currentPhase }
    }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            phases.forEachIndexed { idx, _ ->
                when {
                    idx == activeIdx -> {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                    idx < activeIdx -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(trackColor),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            phases.forEachIndexed { idx, (_, label) ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (idx == activeIdx) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeedGauge(value: Double, unit: String, label: String) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val gaugeMax = if (unit == "ms") {
        when {
            value <= 25.0 -> 25.0
            value <= 50.0 -> 50.0
            value <= 100.0 -> 100.0
            value <= 200.0 -> 200.0
            value <= 500.0 -> 500.0
            else -> 1000.0
        }
    } else {
        when {
            value <= 50.0 -> 50.0
            value <= 100.0 -> 100.0
            value <= 200.0 -> 200.0
            value <= 500.0 -> 500.0
            value <= 1000.0 -> 1000.0
            else -> 2000.0
        }
    }

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 18.dp.toPx()
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(strokePx / 2, strokePx / 2)
            drawArc(
                color = track,
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            val fraction = (value / gaugeMax).coerceIn(0.0, 1.0).toFloat()
            if (fraction > 0f) {
                drawArc(
                    color = primary,
                    startAngle = 150f,
                    sweepAngle = 240f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatSpeed(value),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "满量程 ${gaugeMax.toInt()} $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeedSparkline(
    samples: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val max = (samples.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        drawSeries(samples, color, max)
        drawLine(
            color = Color.Gray.copy(alpha = 0.3f),
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
private fun DualSparkline(
    downloadSamples: List<Double>,
    uploadSamples: List<Double>,
    modifier: Modifier = Modifier,
) {
    val downloadColor = MaterialTheme.colorScheme.primary
    val uploadColor = MaterialTheme.colorScheme.tertiary

    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(downloadColor, "下载")
            LegendDot(uploadColor, "上传")
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val max = maxOf(
                downloadSamples.maxOrNull() ?: 0.0,
                uploadSamples.maxOrNull() ?: 0.0,
            ).coerceAtLeast(1.0)
            drawSeries(downloadSamples, downloadColor, max)
            drawSeries(uploadSamples, uploadColor, max)
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, size.height - 1f),
                end = Offset(size.width, size.height - 1f),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.drawSeries(samples: List<Double>, color: Color, max: Double) {
    if (samples.size < 2) return
    val stepX = size.width / (samples.size - 1)
    val pts = samples.mapIndexed { i, v ->
        Offset(i * stepX, size.height - ((v / max) * size.height).toFloat())
    }
    for (i in 0 until pts.size - 1) {
        drawLine(
            color = color,
            start = pts[i],
            end = pts[i + 1],
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ResultCard(
    title: String,
    value: String,
    unit: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value + if (unit.isNotEmpty()) " $unit" else "",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecentResultRow(result: TestResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timeFormat.format(Date(result.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${result.serverName} · ${result.band}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "↓ ${formatSpeed(result.downloadMbps)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "↑ ${formatSpeed(result.uploadMbps)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${formatMs(result.latencyMs)} ms",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

fun formatSpeed(mbps: Double): String = when {
    mbps < 10.0 -> String.format(Locale.US, "%.2f", mbps)
    mbps < 100.0 -> String.format(Locale.US, "%.1f", mbps)
    else -> String.format(Locale.US, "%.0f", mbps)
}

fun formatMs(ms: Double): String = String.format(Locale.US, "%.1f", ms)
