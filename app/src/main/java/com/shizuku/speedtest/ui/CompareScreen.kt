package com.shizuku.speedtest.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.shizuku.speedtest.AppViewModel
import com.shizuku.speedtest.data.KnownNetwork
import com.shizuku.speedtest.data.TestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val linePalette = listOf(
    Color(0xFF45619D),
    Color(0xFFE8590C),
    Color(0xFF2F9E44),
    Color(0xFF9C36B5),
    Color(0xFF0B7285),
    Color(0xFFE64980),
    Color(0xFF5C940D),
    Color(0xFF1971C2),
)

private val axisDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private enum class Metric(val label: String, val unit: String) {
    DOWNLOAD("下载", "Mbps"),
    UPLOAD("上传", "Mbps"),
    LATENCY("延迟", "ms"),
    JITTER("抖动", "ms"),
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val networks by viewModel.networks.collectAsState()
    val allResults by viewModel.allResults.collectAsState()
    var metric by remember { mutableStateOf(Metric.DOWNLOAD) }
    var hiddenIds by remember { mutableStateOf(emptySet<Long>()) }

    val resultsByNetwork = remember(allResults) {
        allResults.groupBy { it.networkId }
    }
    val networksWithData = networks.filter { resultsByNetwork.containsKey(it.id) }
    val visibleNetworks = networksWithData.filter { it.id !in hiddenIds }

    val colorOf: (KnownNetwork) -> Color = { n ->
        val idx = networksWithData.indexOf(n).coerceAtLeast(0)
        linePalette[idx % linePalette.size]
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric.values().forEach { m ->
                    FilterChip(
                        selected = metric == m,
                        onClick = { metric = m },
                        label = { Text(m.label) },
                    )
                }
            }
        }

        if (networksWithData.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "还没有测速记录，先去测速 Tab 测几次，回来就能对比了。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "${metric.label}对比（${metric.unit}）",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        MultiLineChart(
                            networks = visibleNetworks,
                            resultsByNetwork = resultsByNetwork,
                            metric = metric,
                            colorOf = colorOf,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点下面的网络可以隐藏 / 显示对应的线",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    networksWithData.forEach { n ->
                        val visible = n.id !in hiddenIds
                        FilterChip(
                            selected = visible,
                            onClick = {
                                hiddenIds = if (visible) hiddenIds + n.id else hiddenIds - n.id
                            },
                            label = { Text(n.displayName) },
                            leadingIcon = {
                                Canvas(Modifier.size(10.dp)) {
                                    drawCircle(color = colorOf(n))
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }

            item {
                Text(
                    "统计（${metric.label}，${metric.unit}）",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(visibleNetworks, key = { it.id }) { n ->
                val values = resultsByNetwork[n.id]?.map { valueOf(it, metric) } ?: emptyList()
                if (values.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Canvas(Modifier.size(12.dp)) {
                                drawCircle(color = colorOf(n))
                            }
                            Text(
                                n.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                            )
                            StatText("平均", values.average(), metric)
                            StatText("最高", values.maxOrNull() ?: 0.0, metric)
                            StatText("最低", values.minOrNull() ?: 0.0, metric)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatText(label: String, value: Double, metric: Metric) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.padding(start = 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (metric == Metric.DOWNLOAD || metric == Metric.UPLOAD) {
                formatSpeed(value)
            } else {
                formatMs(value)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MultiLineChart(
    networks: List<KnownNetwork>,
    resultsByNetwork: Map<Long, List<TestResult>>,
    metric: Metric,
    colorOf: (KnownNetwork) -> Color,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val allPoints = networks.flatMap { resultsByNetwork[it.id].orEmpty() }
        if (allPoints.size < 2) {
            if (allPoints.size == 1) {
                val p = allPoints[0]
                drawCircle(
                    color = colorOf(networks.first()),
                    radius = 6.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${formatSpeed(valueOf(p, metric))} ${metric.unit}",
                    size.width / 2f - 40.dp.toPx(),
                    size.height / 2f - 16.dp.toPx(),
                    Paint().apply {
                        color = axisColor.toArgb()
                        textSize = 12.dp.toPx()
                    },
                )
            }
            return@Canvas
        }

        val minT = allPoints.minOf { it.timestamp }.toDouble()
        val maxT = allPoints.maxOf { it.timestamp }.toDouble()
        val spanT = (maxT - minT).coerceAtLeast(1.0)
        val maxV = allPoints.maxOf { valueOf(it, metric) }.coerceAtLeast(1.0) * 1.1

        val leftPad = 44.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val topPad = 8.dp.toPx()
        val plotW = size.width - leftPad - 8.dp.toPx()
        val plotH = size.height - topPad - bottomPad

        fun xOf(t: Long): Float = (leftPad + ((t - minT) / spanT) * plotW).toFloat()
        fun yOf(v: Double): Float = (topPad + plotH - (v / maxV) * plotH).toFloat()

        val textPaint = Paint().apply {
            color = axisColor.toArgb()
            textSize = 11.dp.toPx()
        }

        val steps = 4
        for (i in 0..steps) {
            val v = maxV * i / steps
            val y = yOf(v)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(leftPad + plotW, y),
                strokeWidth = 1.dp.toPx(),
            )
            val label = if (metric == Metric.DOWNLOAD || metric == Metric.UPLOAD) {
                formatSpeed(v)
            } else {
                formatMs(v)
            }
            drawContext.canvas.nativeCanvas.drawText(
                label,
                0f,
                y + 4.dp.toPx(),
                textPaint,
            )
        }

        val startLabel = axisDateFormat.format(Date(minT.toLong()))
        val endLabel = axisDateFormat.format(Date(maxT.toLong()))
        drawContext.canvas.nativeCanvas.drawText(
            startLabel,
            leftPad,
            size.height - 6.dp.toPx(),
            textPaint,
        )
        drawContext.canvas.nativeCanvas.drawText(
            endLabel,
            leftPad + plotW - textPaint.measureText(endLabel),
            size.height - 6.dp.toPx(),
            textPaint,
        )

        networks.forEach { n ->
            val pts = resultsByNetwork[n.id].orEmpty()
                .sortedBy { it.timestamp }
                .map { Offset(xOf(it.timestamp), yOf(valueOf(it, metric))) }
            if (pts.size < 2) {
                pts.firstOrNull()?.let { p ->
                    drawCircle(color = colorOf(n), radius = 5.dp.toPx(), center = p)
                }
                return@forEach
            }
            for (i in 0 until pts.size - 1) {
                drawLine(
                    color = colorOf(n),
                    start = pts[i],
                    end = pts[i + 1],
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            pts.forEach { p ->
                drawCircle(color = colorOf(n), radius = 3.5.dp.toPx(), center = p)
            }
        }
    }
}

private fun valueOf(r: TestResult, metric: Metric): Double = when (metric) {
    Metric.DOWNLOAD -> r.downloadMbps
    Metric.UPLOAD -> r.uploadMbps
    Metric.LATENCY -> r.latencyMs
    Metric.JITTER -> r.jitterMs
}
