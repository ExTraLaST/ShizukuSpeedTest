package com.shizuku.speedtest.net

import android.net.TrafficStats
import android.os.Process
import com.shizuku.speedtest.data.SpeedNode
import com.shizuku.speedtest.data.TrafficTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val requestSeq = AtomicLong(0L)

    companion object {
        private const val SAMPLE_INTERVAL_MS = 200L
        private const val SLIDING_WINDOW_NS = 1_000_000_000L
        private const val UPLOAD_CHUNK_BYTES = 16_384
        private const val UPLOAD_ROTATE_BYTES = 33_554_432L // 32 MB per POST request

        suspend fun probeNode(node: SpeedNode): Double? = withContext(Dispatchers.IO) {
            val host = runCatching { URL(node.baseUrl).host }.getOrNull()
            if (host != null) {
                val ping = pingHost(host, count = 3)
                if (ping != null) return@withContext ping.avgMs
            }
            val engine = SpeedTestEngine()
            var best: Double? = null
            repeat(3) {
                val ms = engine.httpGetTiming(node.latencyUrl(System.nanoTime())).ms
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

    private class SlidingWindowSampler(
        private val windowNs: Long = SLIDING_WINDOW_NS
    ) {
        private val points = ArrayDeque<Pair<Long, Long>>()

        fun record(nowNs: Long, totalBytes: Long): Double? {
            if (points.isNotEmpty() && totalBytes < points.last().second) {
                points.clear()
            }
            points.addLast(nowNs to totalBytes)
            while (points.size > 2 && nowNs - points[1].first >= windowNs) {
                points.removeFirst()
            }
            val oldest = points.first()
            val dtNs = nowNs - oldest.first
            if (dtNs < 150_000_000L) return null
            val deltaBytes = (totalBytes - oldest.second).coerceAtLeast(0L)
            return (deltaBytes * 8.0 / (dtNs / 1_000_000_000.0)) / 1_000_000.0
        }
    }

    private fun disconnectAll(connections: MutableSet<HttpURLConnection>) {
        val snapshot = connections.toList()
        connections.clear()
        for (conn in snapshot) {
            runCatching { conn.disconnect() }
        }
    }

    fun cancel() {
        cancelled = true
        disconnectAll(activeConnections)
    }

    private fun checkCancelled() {
        if (cancelled) throw TestCancelledException()
    }

    private fun nextCacheBuster(): Long =
        System.nanoTime() xor (requestSeq.incrementAndGet() shl 20)

    suspend fun run(
        node: SpeedNode,
        tier: TrafficTier,
        gatewayIp: String?,
        onSample: (LiveSample) -> Unit
    ): TestOutcome = withContext(Dispatchers.IO) {
        cancelled = false
        activeConnections.clear()

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
            httpGetTiming(node.latencyUrl(nextCacheBuster())).error?.let { lastError = it }
        }

        val samples = mutableListOf<Double>()
        var failures = 0
        repeat(count) {
            checkCancelled()
            val timing = httpGetTiming(node.latencyUrl(nextCacheBuster()))
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
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-store, no-cache")
                setRequestProperty("Connection", "keep-alive")
            }
            activeConnections.add(conn)
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
            conn?.let {
                activeConnections.remove(it)
                runCatching { it.disconnect() }
            }
        }
    }

    /**
     * Duration-based continuous streaming download:
     * Keeps all concurrent connections saturated until the phase deadline,
     * skips handshake + TCP Slow Start warmup anchored to first received byte,
     * smooths live/chart points with a 1-second sliding window, and computes
     * final throughput from exact steady-state bytes / duration.
     */
    private suspend fun measureDownload(
        node: SpeedNode,
        tier: TrafficTier,
        onSample: (LiveSample) -> Unit
    ): Pair<Double, List<Double>> = coroutineScope {
        val concurrency = tier.concurrency
        val durationSec = tier.durationSec
        val (chunkBytes, warmupNs) = when (tier) {
            TrafficTier.LIGHT -> 4_194_304L to 1_200_000_000L
            TrafficTier.STANDARD -> 10_485_760L to 1_800_000_000L
            TrafficTier.FULL -> 20_971_520L to 2_000_000_000L
        }

        val totalBytes = AtomicLong(0L)
        val firstByteNs = AtomicLong(0L)
        val lastError = AtomicReference<IOException?>(null)
        val phaseConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val allSamples = mutableListOf<Double>()
        val steadySamples = mutableListOf<Double>()

        val startNs = System.nanoTime()
        val deadlineNs = startNs + durationSec.toLong() * 1_000_000_000L

        var steadyStartNs = 0L
        var steadyStartBytes = 0L

        onSample(LiveSample(TestPhase.DOWNLOAD, 0.0))

        val watchdog = launch(Dispatchers.IO) {
            val remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainMs > 0L) delay(remainMs)
            disconnectAll(phaseConnections)
        }

        val sampler = launch {
            val window = SlidingWindowSampler()
            var seeded = false
            while (!cancelled && System.nanoTime() < deadlineNs) {
                delay(SAMPLE_INTERVAL_MS)
                val fbNs = firstByteNs.get()
                if (fbNs == 0L) continue
                val nowNs = System.nanoTime()
                val curBytes = totalBytes.get()
                if (!seeded) {
                    window.record(fbNs, 0L)
                    seeded = true
                }
                val mbps = window.record(nowNs, curBytes) ?: continue
                allSamples.add(mbps)
                onSample(LiveSample(TestPhase.DOWNLOAD, mbps))
                if (nowNs - fbNs >= warmupNs) {
                    if (steadyStartNs == 0L) {
                        steadyStartNs = nowNs
                        steadyStartBytes = curBytes
                    }
                    steadySamples.add(mbps)
                }
            }
        }

        val jobs = (0 until concurrency).map {
            async(Dispatchers.IO) {
                val buf = ByteArray(32_768)
                var consecutiveErrors = 0
                while (!cancelled && System.nanoTime() < deadlineNs) {
                    var conn: HttpURLConnection? = null
                    var gotBytesThisReq = false
                    try {
                        val url = node.downloadUrl(chunkBytes, nextCacheBuster())
                        conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 15_000
                            requestMethod = "GET"
                            useCaches = false
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Cache-Control", "no-store, no-cache")
                            setRequestProperty("Connection", "keep-alive")
                        }
                        activeConnections.add(conn)
                        phaseConnections.add(conn)
                        conn.connect()
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            throw IOException("HTTP $code")
                        }
                        conn.inputStream.use { input ->
                            while (!cancelled && System.nanoTime() < deadlineNs) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                firstByteNs.compareAndSet(0L, System.nanoTime())
                                totalBytes.addAndGet(n.toLong())
                                gotBytesThisReq = true
                            }
                        }
                        if (gotBytesThisReq) {
                            consecutiveErrors = 0
                        }
                    } catch (e: IOException) {
                        if (cancelled) throw TestCancelledException()
                        if (System.nanoTime() >= deadlineNs) break
                        lastError.set(e)
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) break
                        delay(100L)
                    } finally {
                        conn?.let {
                            activeConnections.remove(it)
                            phaseConnections.remove(it)
                            runCatching { it.disconnect() }
                        }
                    }
                }
            }
        }

        try {
            jobs.awaitAll()
        } finally {
            watchdog.cancel()
            disconnectAll(phaseConnections)
            sampler.cancelAndJoin()
        }

        checkCancelled()

        val bytes = totalBytes.get()
        if (bytes == 0L) {
            throw lastError.get() ?: IOException("下载测速无数据：${node.baseUrl}")
        }

        val endNs = min(System.nanoTime(), deadlineNs)
        val finalMbps = when {
            steadyStartNs > 0L &&
                endNs - steadyStartNs >= 500_000_000L &&
                bytes > steadyStartBytes -> {
                val steadyDt = (endNs - steadyStartNs) / 1_000_000_000.0
                ((bytes - steadyStartBytes) * 8.0 / steadyDt) / 1_000_000.0
            }
            steadySamples.isNotEmpty() -> steadySamples.average()
            else -> {
                val baseNs = if (firstByteNs.get() > 0L) firstByteNs.get() else startNs
                val dt = ((endNs - baseNs) / 1_000_000_000.0).coerceAtLeast(0.05)
                (bytes * 8.0 / dt) / 1_000_000.0
            }
        }

        val chartSamples = when {
            steadySamples.size >= 2 -> steadySamples
            allSamples.size >= 2 -> allSamples
            else -> listOf(finalMbps, finalMbps)
        }

        finalMbps to chartSamples
    }

    /**
     * v1.8+ streaming upload by duration:
     * Streams chunked payloads concurrently until the time limit is reached
     * (rotating POST requests every 32 MB on fast links to maintain full concurrency),
     * then disconnects immediately without waiting for response.
     * Uses Android UID wire-layer TX counters (TrafficStats) with app-byte fallback,
     * post-handshake warmup, 1-second sliding window, and median-based spike filtering
     * to avoid SO_SNDBUF / router bufferbloat 1/x decay artifacts.
     * Final upload speed uses the mean of steady-state samples.
     */
    private suspend fun measureUpload(
        node: SpeedNode,
        tier: TrafficTier,
        onSample: (LiveSample) -> Unit
    ): Pair<Double, List<Double>> = coroutineScope {
        val concurrency = tier.concurrency
        val durationSec = tier.durationSec
        val (anchorDelayNs, warmupNs) = when (tier) {
            TrafficTier.LIGHT -> 600_000_000L to 1_500_000_000L
            TrafficTier.STANDARD -> 1_000_000_000L to 2_000_000_000L
            TrafficTier.FULL -> 1_200_000_000L to 2_200_000_000L
        }

        val myUid = Process.myUid()
        val txBaseline = runCatching { TrafficStats.getUidTxBytes(myUid) }.getOrDefault(-1L)
        val hasTxStats = txBaseline >= 0L

        val totalBytes = AtomicLong(0L)
        val firstByteNs = AtomicLong(0L)
        val lastError = AtomicReference<IOException?>(null)
        val phaseConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val steadySamples = mutableListOf<Double>()
        val fallbackSamples = mutableListOf<Double>()

        val startNs = System.nanoTime()
        val deadlineNs = startNs + durationSec.toLong() * 1_000_000_000L
        val chunk = ByteArray(UPLOAD_CHUNK_BYTES) { i -> ((i * 31) % 256).toByte() }

        var txActive = false

        onSample(LiveSample(TestPhase.UPLOAD, 0.0))

        val watchdog = launch(Dispatchers.IO) {
            val remainMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainMs > 0L) delay(remainMs)
            disconnectAll(phaseConnections)
        }

        val sampler = launch {
            val txWindow = SlidingWindowSampler()
            val appWindow = SlidingWindowSampler()
            while (!cancelled && System.nanoTime() < deadlineNs) {
                delay(SAMPLE_INTERVAL_MS)
                val fbNs = firstByteNs.get()
                if (fbNs == 0L) continue
                val nowNs = System.nanoTime()
                val sinceFirstNs = nowNs - fbNs
                if (sinceFirstNs < anchorDelayNs) continue

                val appRate = appWindow.record(nowNs, totalBytes.get())
                var txRate: Double? = null
                if (hasTxStats) {
                    val curTx = runCatching { TrafficStats.getUidTxBytes(myUid) }.getOrDefault(-1L)
                    if (curTx >= txBaseline) {
                        val txDelta = curTx - txBaseline
                        if (txDelta > 0L) txActive = true
                        if (txActive) {
                            txRate = txWindow.record(nowNs, txDelta)
                        }
                    }
                }

                val mbps = if (txActive) txRate ?: appRate else appRate
                if (mbps != null && mbps > 0.0) {
                    fallbackSamples.add(mbps)
                    if (sinceFirstNs >= warmupNs) {
                        steadySamples.add(mbps)
                        onSample(LiveSample(TestPhase.UPLOAD, mbps))
                    }
                }
            }
        }

        val jobs = (0 until concurrency).map {
            async(Dispatchers.IO) {
                var consecutiveErrors = 0
                while (!cancelled && System.nanoTime() < deadlineNs) {
                    var conn: HttpURLConnection? = null
                    var sentThisReq = 0L
                    try {
                        conn = (URL(node.uploadUrl()).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 15_000
                            requestMethod = "POST"
                            doOutput = true
                            useCaches = false
                            setChunkedStreamingMode(chunk.size)
                            setRequestProperty("Content-Type", "application/octet-stream")
                            setRequestProperty("Connection", "keep-alive")
                        }
                        activeConnections.add(conn)
                        phaseConnections.add(conn)
                        conn.connect()
                        conn.outputStream.use { out ->
                            while (!cancelled &&
                                System.nanoTime() < deadlineNs &&
                                sentThisReq < UPLOAD_ROTATE_BYTES
                            ) {
                                val toWrite = min(
                                    chunk.size.toLong(),
                                    UPLOAD_ROTATE_BYTES - sentThisReq
                                ).toInt()
                                out.write(chunk, 0, toWrite)
                                firstByteNs.compareAndSet(0L, System.nanoTime())
                                totalBytes.addAndGet(toWrite.toLong())
                                sentThisReq += toWrite.toLong()
                            }
                            if (!cancelled && System.nanoTime() < deadlineNs) {
                                runCatching { out.flush() }
                            }
                        }
                        if (!cancelled && System.nanoTime() < deadlineNs) {
                            runCatching {
                                conn.responseCode
                                conn.inputStream?.use { it.readBytes() }
                            }
                        }
                        if (sentThisReq > 0L) {
                            consecutiveErrors = 0
                        }
                    } catch (e: IOException) {
                        if (cancelled) throw TestCancelledException()
                        if (System.nanoTime() >= deadlineNs) break
                        lastError.set(e)
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) break
                        delay(100L)
                    } finally {
                        conn?.let {
                            activeConnections.remove(it)
                            phaseConnections.remove(it)
                            runCatching { it.disconnect() }
                        }
                    }
                }
            }
        }

        try {
            jobs.awaitAll()
        } finally {
            watchdog.cancel()
            disconnectAll(phaseConnections)
            sampler.cancelAndJoin()
        }

        checkCancelled()

        val bytes = totalBytes.get()
        if (bytes == 0L) {
            throw lastError.get() ?: IOException("上传测速无数据：${node.baseUrl}")
        }

        val candidates = if (steadySamples.size >= 2) steadySamples else fallbackSamples
        val (finalMbps, finalSamples) = if (candidates.size >= 2) {
            val sorted = candidates.sorted()
            val median = sorted[sorted.size / 2]
            val cleaned = candidates.filter { it <= 2.2 * median }
            val steady = if (cleaned.size >= 2) cleaned else candidates
            steady.average() to steady
        } else {
            val baseNs = if (firstByteNs.get() > 0L) firstByteNs.get() else startNs
            val endNs = min(System.nanoTime(), deadlineNs)
            val elapsedSec = ((endNs - baseNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            val finalTxDelta = if (txActive) {
                (runCatching { TrafficStats.getUidTxBytes(myUid) }.getOrDefault(-1L) - txBaseline)
                    .coerceAtLeast(0L)
            } else {
                0L
            }
            val effectiveBytes = if (finalTxDelta > 0L) finalTxDelta else bytes
            val overallMbps = (effectiveBytes * 8.0 / elapsedSec) / 1_000_000.0
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
