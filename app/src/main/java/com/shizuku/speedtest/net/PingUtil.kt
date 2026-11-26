package com.shizuku.speedtest.net

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

data class PingStats(
    val avgMs: Double,
    val jitterMs: Double,
    val loss: Double,
    val samples: List<Double>
)

fun pingHost(
    host: String,
    count: Int,
    timeoutSec: Int = 2,
    onSample: ((Double) -> Unit)? = null
): PingStats? {
    return try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("ping", "-c", count.toString(), "-W", timeoutSec.toString(), "-i", "0.2", host)
        )
        val timeRegex = Regex("""time[=<]([\d.]+)\s*ms""")
        val samples = mutableListOf<Double>()

        val readerThread = thread(start = true, isDaemon = true) {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val match = timeRegex.find(line)
                        val ms = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                        if (ms != null) {
                            samples.add(ms)
                            onSample?.invoke(ms)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val finished = proc.waitFor(((timeoutSec + 1) * count + 5).toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            readerThread.join(1000L)
            return null
        }
        readerThread.join(2000L)

        if (samples.isEmpty()) return null

        val avg = samples.average()
        val jitter = if (samples.size > 1) {
            samples.zipWithNext { a, b -> abs(a - b) }.average()
        } else {
            0.0
        }
        val loss = (1.0 - samples.size.toDouble() / count.toDouble()).coerceIn(0.0, 1.0)
        PingStats(avg, jitter, loss, samples)
    } catch (_: Exception) {
        null
    }
}
