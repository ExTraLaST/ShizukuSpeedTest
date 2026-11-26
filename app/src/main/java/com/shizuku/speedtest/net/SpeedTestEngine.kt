package com.shizuku.speedtest.net

import com.shizuku.speedtest.data.SpeedNode
import com.shizuku.speedtest.data.TrafficTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.math.abs
import kotlin.math.min

enum class TestPhase {
    LATENCY,
    DOWNLOAD,
    UPLOAD
}

data class LiveSample(
    val phase: TestPhase,
    val value: Double
)

data class TestOutcome(
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Double,
    val jitterMs: Double,
    val packetLoss: Double,
    val gatewayLatencyMs: Double?,
    val gatewayJitterMs: Double?,
    val gatewayLoss: Double?,
    val downloadSamples: List<Double>,
    val uploadSamples: List<Double>
)

class TestCancelledException : CancellationException("测速已取消")

class SpeedTestEngine {
    @Volatile
    private var cancelled: Boolean = false

    companion object {
        suspend fun probeNode(node: SpeedNode): Double? = withContext(Dispatchers.IO) {
            val host = runCatching { URL(node.baseUrl).host }.getOrNull()
            if (host != null) {
                val ping = pingHost(host, count = 3)
                if (ping != null) return@withContext ping.avgMs
            }
            val engine = SpeedTestEngine()
            var best: Double? = null
            repeat(3) {
                val ms = engine.httpGetTiming(node.latencyUrl()).ms
                if (ms != null) {
                    best = if (best == null) ms else min(best!!, ms)
                }
            }
            best
        }
    }

    private data class HttpTiming(
        val ms: Double?,
        val error: String?
    )

    fun cancel() {
        cancelled = true
    }

    private fun checkCancelled() {
        if (cancelled) throw TestCancelledException()
    }

    suspend fun run(
        node: SpeedNode,
        tier: TrafficTier,
        gatewayIp: String?,
        onSample: (LiveSample) -> Unit
    ): TestOutcome = withContext(Dispatchers.IO) {
        cancelled = false

        val latencyCount = when (tier) {
            TrafficTier.LIGHT -> 5
            TrafficTier.STANDARD -> 10
            TrafficTier.FULL -> 20
        }

        onSample(LiveSample(TestPhase.LATENCY, 0.0))

        val latencyPair = withTimeoutOrNull(60_000L) {
            coroutineScope {
                val nodeDeferred = async {
                    measureNodeLatency(node, latencyCount, onSample)
                }
                val gwDeferred = async {
                    if (gatewayIp != null) pingHost(gatewayIp, count = 10) else null
                }
                nodeDeferred.await() to gwDeferred.await()
            }
        } ?: throw IOException("延迟测试超时（60 秒），节点无响应")

        val (nodeLatency, gwStats) = latencyPair
        val (latencyMs, jitterMs, packetLoss) = nodeLatency

        checkCancelled()

        val downloadResult = withTimeoutOrNull(120_000L) {
            try {
                measureDownload(node, tier, onSample)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw IOException("下载失败：${friendlyNetError(e)}")
            }
        } ?: throw IOException("下载测速超时（120 秒），节点响应太慢或网络过慢")

        val (downloadMbps, downloadSamples) = downloadResult

        checkCancelled()

        val uploadResult = withTimeoutOrNull(120_000L) {
            try {
                measureUpload(node, tier, onSample)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw IOException("上传失败：${friendlyNetError(e)}")
            }
        } ?: throw IOException("上传测速超时（120 秒），节点响应太慢或网络过慢")

        val (uploadMbps, uploadSamples) = uploadResult

        TestOutcome(
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            latencyMs = latencyMs,
            jitterMs = jitterMs,
            packetLoss = packetLoss,
            gatewayLatencyMs = gwStats?.avgMs,
            gatewayJitterMs = gwStats?.jitterMs,
            gatewayLoss = gwStats?.loss,
            downloadSamples = downloadSamples,
            uploadSamples = uploadSamples
        )
    }

    private suspend fun measureNodeLatency(
        node: SpeedNode,
        count: Int,
        onSample: (LiveSample) -> Unit
    ): Triple<Double, Double, Double> {
        val host = runCatching { URL(node.baseUrl).host }.getOrNull()
        if (host != null) {
            val probe = withContext(Dispatchers.IO) {
                pingHost(host, count = 3, timeoutSec = 1) { ms ->
                    onSample(LiveSample(TestPhase.LATENCY, ms))
                }
            }
            if (probe != null) {
                val ping = withContext(Dispatchers.IO) {
                    pingHost(host, count = count) { ms ->
                        onSample(LiveSample(TestPhase.LATENCY, ms))
                    }
                }
                if (ping != null) {
                    return Triple(ping.avgMs, ping.jitterMs, ping.loss)
                }
            }
        }

        var lastError: String? = null
        repeat(2) {
            checkCancelled()
            httpGetTiming(node.latencyUrl()).error?.let { lastError = it }
        }

        val samples = mutableListOf<Double>()
        var failures = 0
        repeat(count) {
            checkCancelled()
            val timing = httpGetTiming(node.latencyUrl())
            if (timing.ms != null) {
                samples.add(timing.ms)
                onSample(LiveSample(TestPhase.LATENCY, timing.ms))
            } else {
                failures++
                timing.error?.let { lastError = it }
            }
        }

        if (samples.isEmpty()) {
            throw IOException("节点无响应：${node.baseUrl}（${lastError ?: "无响应"}）")
        }

        val avg = samples.average()
        val jitter = if (samples.size > 1) {
            samples.zipWithNext { a, b -> abs(a - b) }.average()
        } else {
            0.0
        }
        val loss = failures.toDouble() / count.toDouble()
        return Triple(avg, jitter, loss)
    }

    private fun httpGetTiming(url: String): HttpTiming {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("Connection", "keep-alive")
            }
            val startNs = System.nanoTime()
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                return HttpTiming(null, "HTTP $code")
            }
            conn.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - startNs) / 1_000_000.0
            HttpTiming(ms, null)
        } catch (e: IOException) {
            HttpTiming(null, friendlyNetError(e))
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun measureDownload(
        node: SpeedNode,
        tier: TrafficTier,
        onSample: (LiveSample) -> Unit
    ): Pair<Double, List<Double>> = coroutineScope {
        val (concurrency, bytesPerConn) = when (tier) {
            TrafficTier.LIGHT -> 2 to 2_097_152L
            TrafficTier.STANDARD -> 4 to 10_485_760L
            TrafficTier.FULL -> 6 to 26_214_400L
        }

        val totalBytes = AtomicLong(0L)
        val samples = mutableListOf<Double>()
        val startNs = System.nanoTime()

        val sampler = launch {
            var lastNs = startNs
            var lastBytes = 0L
            while (true) {
                delay(200L)
                val nowNs = System.nanoTime()
                val curBytes = totalBytes.get()
                val dt = (nowNs - lastNs) / 1_000_000_000.0
                if (dt > 0.0) {
                    val mbps = ((curBytes - lastBytes) * 8.0 / dt) / 1_000_000.0
                    samples.add(mbps)
                    onSample(LiveSample(TestPhase.DOWNLOAD, mbps))
                }
                lastNs = nowNs
                lastBytes = curBytes
            }
        }

        val jobs = (0 until concurrency).map {
            async(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(node.downloadUrl(bytesPerConn)).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 30_000
                        requestMethod = "GET"
                    }
                    conn.connect()
                    val buf = ByteArray(65_536)
                    conn.inputStream.use { input ->
                        while (true) {
                            checkCancelled()
                            val n = input.read(buf)
                            if (n <= 0) break
                            totalBytes.addAndGet(n.toLong())
                        }
                    }
                } finally {
                    conn?.disconnect()
                }
            }
        }

        jobs.awaitAll()
        sampler.cancel()

        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
        val bytes = totalBytes.get()
        if (bytes == 0L) {
            throw IOException("下载测速无数据：${node.baseUrl}")
        }
        val mbps = (bytes * 8.0 / elapsedSec) / 1_000_000.0
        mbps to samples
    }

    /**
     * v1.8+ streaming upload by duration:
     * Streams chunked payloads concurrently until the time limit (or per-connection safety cap)
     * is reached, then disconnects immediately without waiting for response.
     * Final upload speed uses the mean of steady-state (post-1s warmup) interval samples
     * after filtering spikes > 3x mean.
     */
    private suspend fun measureUpload(
        node: SpeedNode,
        tier: TrafficTier,
        onSample: (LiveSample) -> Unit
    ): Pair<Double, List<Double>> = coroutineScope {
        val (concurrency, durationSec, capPerConn) = when (tier) {
            TrafficTier.LIGHT -> Triple(2, 5, 33_554_432L)
            TrafficTier.STANDARD -> Triple(4, 10, 67_108_864L)
            TrafficTier.FULL -> Triple(6, 15, 134_217_728L)
        }

        val totalBytes = AtomicLong(0L)
        val samples = mutableListOf<Double>()
        val startNs = System.nanoTime()
        val deadlineNs = startNs + durationSec.toLong() * 1_000_000_000L

        val chunk = ByteArray(65_536) { i -> ((i * 31) % 256).toByte() }

        onSample(LiveSample(TestPhase.UPLOAD, 0.0))

        val sampler = launch {
            var lastNs = startNs
            var lastBytes = 0L
            while (System.nanoTime() < deadlineNs) {
                delay(200L)
                val nowNs = System.nanoTime()
                val curBytes = totalBytes.get()
                val dt = (nowNs - lastNs) / 1_000_000_000.0
                val elapsed = (nowNs - startNs) / 1_000_000_000.0
                if (dt > 0.0 && elapsed >= 1.0) {
                    val mbps = ((curBytes - lastBytes) * 8.0 / dt) / 1_000_000.0
                    samples.add(mbps)
                    onSample(LiveSample(TestPhase.UPLOAD, mbps))
                }
                lastNs = nowNs
                lastBytes = curBytes
            }
        }

        val jobs = (0 until concurrency).map {
            async(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(node.uploadUrl()).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 30_000
                        requestMethod = "POST"
                        doOutput = true
                        setChunkedStreamingMode(65_536)
                        setRequestProperty("Content-Type", "application/octet-stream")
                    }
                    conn.connect()
                    conn.outputStream.use { out ->
                        var sent = 0L
                        while (System.nanoTime() < deadlineNs && sent < capPerConn) {
                            checkCancelled()
                            val toWrite = min(chunk.size.toLong(), capPerConn - sent).toInt()
                            out.write(chunk, 0, toWrite)
                            totalBytes.addAndGet(toWrite.toLong())
                            sent += toWrite.toLong()
                        }
                        out.flush()
                    }
                } finally {
                    conn?.disconnect()
                }
            }
        }

        jobs.awaitAll()
        sampler.cancel()

        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
        val bytes = totalBytes.get()
        if (bytes == 0L) {
            throw IOException("上传测速无数据：${node.baseUrl}")
        }

        val overallMbps = (bytes * 8.0 / elapsedSec) / 1_000_000.0
        val positive = samples.filter { it > 0.0 }
        val (finalMbps, finalSamples) = if (positive.size >= 2) {
            val mean = positive.average()
            val cleaned = positive.filter { it <= 3.0 * mean }
            val steady = if (cleaned.size >= 2) cleaned else positive
            steady.average() to steady
        } else {
            overallMbps to listOf(overallMbps, overallMbps)
        }

        finalMbps to finalSamples
    }

    private fun friendlyNetError(e: IOException): String = when (e) {
        is UnknownHostException -> "DNS 解析失败"
        is SocketTimeoutException -> "连接超时"
        is ConnectException -> "连接被拒"
        is SSLException -> "TLS 握手失败"
        else -> e.message?.take(40)?.takeIf { it.isNotBlank() } ?: "网络错误"
    }
}
