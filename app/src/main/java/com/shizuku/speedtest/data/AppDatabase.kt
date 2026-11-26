package com.shizuku.speedtest.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "speedtest.db",
    null,
    3
) {
    companion object {
        val BUILTIN_NODES: List<Triple<String, String, String>> = listOf(
            Triple("上海大学（上海）", "https://speedtest4.cc.shu.edu.cn", SpeedNode.PROTOCOL_LIBRESPEED),
            Triple("南京大学（南京）", "https://fs.nju.edu.cn/speed", SpeedNode.PROTOCOL_LIBRESPEED_ROOT),
            Triple("同济大学（上海）", "https://dev.tongji.edu.cn/speedtest", SpeedNode.PROTOCOL_LIBRESPEED),
            Triple("江苏大学（电信）", "http://speed.ujs.edu.cn", SpeedNode.PROTOCOL_LIBRESPEED),
            Triple("云南师范大学（昆明）", "https://speedtest.ynnu.edu.cn", SpeedNode.PROTOCOL_LIBRESPEED),
            Triple("香港 BGP", "https://hkbgp.zgocloud.org", SpeedNode.PROTOCOL_LIBRESPEED),
            Triple("东京 BGP", "https://jpbgp.zgocloud.org", SpeedNode.PROTOCOL_LIBRESPEED),
            Triple("Cloudflare（国际）", "https://speed.cloudflare.com", SpeedNode.PROTOCOL_CLOUDFLARE)
        )

        private fun seedBuiltinNode(
            db: SQLiteDatabase,
            name: String,
            baseUrl: String,
            protocol: String
        ) {
            db.rawQuery("SELECT COUNT(*) FROM nodes WHERE base_url=?", arrayOf(baseUrl)).use { c ->
                if (c.moveToFirst() && c.getInt(0) > 0) return
            }
            val values = ContentValues().apply {
                put("name", name)
                put("base_url", baseUrl)
                put("protocol", protocol)
                put("builtin", 1)
            }
            db.insert("nodes", null, values)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE networks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ssid TEXT NOT NULL,
                bssid TEXT NOT NULL,
                alias TEXT NOT NULL DEFAULT '',
                default_node_id INTEGER,
                last_seen INTEGER NOT NULL DEFAULT 0,
                UNIQUE(ssid, bssid)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE nodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                base_url TEXT NOT NULL,
                protocol TEXT NOT NULL DEFAULT 'cloudflare',
                builtin INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                network_id INTEGER NOT NULL,
                node_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                download_mbps REAL NOT NULL,
                upload_mbps REAL NOT NULL,
                latency_ms REAL NOT NULL,
                jitter_ms REAL NOT NULL,
                packet_loss REAL NOT NULL,
                rssi INTEGER NOT NULL,
                band TEXT NOT NULL,
                link_speed_mbps INTEGER NOT NULL,
                server_name TEXT NOT NULL,
                gateway_latency_ms REAL,
                gateway_jitter_ms REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_results_network_time ON results(network_id, timestamp)")
        db.execSQL("CREATE INDEX idx_results_time ON results(timestamp)")

        for ((name, baseUrl, protocol) in BUILTIN_NODES) {
            seedBuiltinNode(db, name, baseUrl, protocol)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "UPDATE nodes SET name='Cloudflare（国际）' WHERE base_url='https://speed.cloudflare.com' AND builtin=1"
            )
            for ((name, baseUrl, protocol) in BUILTIN_NODES) {
                seedBuiltinNode(db, name, baseUrl, protocol)
            }
        }
        if (oldVersion < 3) {
            db.execSQL(
                "DELETE FROM nodes WHERE base_url IN ('https://speed.nuaa.edu.cn', 'http://speed.nuaa.edu.cn') AND builtin=1"
            )
            db.execSQL(
                "UPDATE networks SET default_node_id = NULL WHERE default_node_id IS NOT NULL AND default_node_id NOT IN (SELECT id FROM nodes)"
            )
            for ((name, baseUrl, protocol) in BUILTIN_NODES) {
                seedBuiltinNode(db, name, baseUrl, protocol)
            }
        }
    }

    private fun toNetwork(cursor: Cursor): KnownNetwork {
        val defaultNodeIdx = cursor.getColumnIndexOrThrow("default_node_id")
        return KnownNetwork(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            ssid = cursor.getString(cursor.getColumnIndexOrThrow("ssid")),
            bssid = cursor.getString(cursor.getColumnIndexOrThrow("bssid")),
            alias = cursor.getString(cursor.getColumnIndexOrThrow("alias")),
            defaultNodeId = if (cursor.isNull(defaultNodeIdx)) null else cursor.getLong(defaultNodeIdx),
            lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
        )
    }

    private fun toNode(cursor: Cursor): SpeedNode {
        return SpeedNode(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
            baseUrl = cursor.getString(cursor.getColumnIndexOrThrow("base_url")),
            protocol = cursor.getString(cursor.getColumnIndexOrThrow("protocol")),
            builtin = cursor.getInt(cursor.getColumnIndexOrThrow("builtin")) == 1
        )
    }

    private fun toResult(cursor: Cursor): TestResult {
        val gwLatIdx = cursor.getColumnIndexOrThrow("gateway_latency_ms")
        val gwJitIdx = cursor.getColumnIndexOrThrow("gateway_jitter_ms")
        return TestResult(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            networkId = cursor.getLong(cursor.getColumnIndexOrThrow("network_id")),
            nodeId = cursor.getLong(cursor.getColumnIndexOrThrow("node_id")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            downloadMbps = cursor.getDouble(cursor.getColumnIndexOrThrow("download_mbps")),
            uploadMbps = cursor.getDouble(cursor.getColumnIndexOrThrow("upload_mbps")),
            latencyMs = cursor.getDouble(cursor.getColumnIndexOrThrow("latency_ms")),
            jitterMs = cursor.getDouble(cursor.getColumnIndexOrThrow("jitter_ms")),
            packetLoss = cursor.getDouble(cursor.getColumnIndexOrThrow("packet_loss")),
            rssi = cursor.getInt(cursor.getColumnIndexOrThrow("rssi")),
            band = cursor.getString(cursor.getColumnIndexOrThrow("band")),
            linkSpeedMbps = cursor.getInt(cursor.getColumnIndexOrThrow("link_speed_mbps")),
            serverName = cursor.getString(cursor.getColumnIndexOrThrow("server_name")),
            gatewayLatencyMs = if (cursor.isNull(gwLatIdx)) null else cursor.getDouble(gwLatIdx),
            gatewayJitterMs = if (cursor.isNull(gwJitIdx)) null else cursor.getDouble(gwJitIdx)
        )
    }

    fun getNetworks(): List<KnownNetwork> {
        readableDatabase.query("networks", null, null, null, null, null, "last_seen DESC").use { c ->
            val list = mutableListOf<KnownNetwork>()
            while (c.moveToNext()) {
                list.add(toNetwork(c))
            }
            return list
        }
    }

    fun getNetwork(id: Long): KnownNetwork? {
        readableDatabase.query("networks", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) toNetwork(c) else null
        }
    }

    fun getNetworkByIdentity(ssid: String, bssid: String): KnownNetwork? {
        readableDatabase.query(
            "networks", null, "ssid=? AND bssid=?", arrayOf(ssid, bssid), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) toNetwork(c) else null
        }
    }

    fun getOrCreateNetwork(ssid: String, bssid: String): KnownNetwork {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.query("networks", null, "ssid=? AND bssid=?", arrayOf(ssid, bssid), null, null, null).use { c ->
            if (c.moveToFirst()) {
                val existing = toNetwork(c)
                val cv = ContentValues().apply { put("last_seen", now) }
                db.update("networks", cv, "id=?", arrayOf(existing.id.toString()))
                return existing.copy(lastSeen = now)
            }
        }
        val cv = ContentValues().apply {
            put("ssid", ssid)
            put("bssid", bssid)
            put("last_seen", now)
        }
        val id = db.insert("networks", null, cv)
        return KnownNetwork(id = id, ssid = ssid, bssid = bssid, lastSeen = now)
    }

    fun updateNetworkAlias(networkId: Long, alias: String) {
        val cv = ContentValues().apply { put("alias", alias) }
        writableDatabase.update("networks", cv, "id=?", arrayOf(networkId.toString()))
    }

    fun updateNetworkDefaultNode(networkId: Long, nodeId: Long?) {
        val cv = ContentValues().apply {
            if (nodeId == null) putNull("default_node_id") else put("default_node_id", nodeId)
        }
        writableDatabase.update("networks", cv, "id=?", arrayOf(networkId.toString()))
    }

    fun mergeNetwork(sourceId: Long, targetId: Long): Int {
        if (sourceId == targetId) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val source = db.query("networks", null, "id=?", arrayOf(sourceId.toString()), null, null, null)
                .use { if (it.moveToFirst()) toNetwork(it) else null } ?: return 0
            val target = db.query("networks", null, "id=?", arrayOf(targetId.toString()), null, null, null)
                .use { if (it.moveToFirst()) toNetwork(it) else null } ?: return 0

            val updates = ContentValues()
            if (target.alias.isBlank() && source.alias.isNotBlank()) {
                updates.put("alias", source.alias)
            }
            if (target.defaultNodeId == null && source.defaultNodeId != null) {
                updates.put("default_node_id", source.defaultNodeId)
            }
            if (updates.size() > 0) {
                db.update("networks", updates, "id=?", arrayOf(targetId.toString()))
            }

            val resultUpdate = ContentValues().apply { put("network_id", targetId) }
            val moved = db.update("results", resultUpdate, "network_id=?", arrayOf(sourceId.toString()))
            db.delete("networks", "id=?", arrayOf(sourceId.toString()))
            db.setTransactionSuccessful()
            return moved
        } finally {
            db.endTransaction()
        }
    }

    fun getNodes(): List<SpeedNode> {
        readableDatabase.query("nodes", null, null, null, null, null, "builtin DESC, id ASC").use { c ->
            val list = mutableListOf<SpeedNode>()
            while (c.moveToNext()) {
                list.add(toNode(c))
            }
            return list
        }
    }

    fun getNode(id: Long): SpeedNode? {
        readableDatabase.query("nodes", null, "id=?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) toNode(c) else null
        }
    }

    fun getNodeByUrl(baseUrl: String): SpeedNode? {
        val normalized = baseUrl.trimEnd('/')
        readableDatabase.query("nodes", null, "base_url=?", arrayOf(normalized), null, null, null).use { c ->
            return if (c.moveToFirst()) toNode(c) else null
        }
    }

    fun insertNode(node: SpeedNode): Long {
        val cv = ContentValues().apply {
            put("name", node.name)
            put("base_url", node.baseUrl.trimEnd('/'))
            put("protocol", node.protocol)
            put("builtin", if (node.builtin) 1 else 0)
        }
        return writableDatabase.insert("nodes", null, cv)
    }

    fun updateNode(node: SpeedNode) {
        val cv = ContentValues().apply {
            put("name", node.name)
            put("base_url", node.baseUrl.trimEnd('/'))
            put("protocol", node.protocol)
        }
        writableDatabase.update("nodes", cv, "id=?", arrayOf(node.id.toString()))
    }

    fun deleteNode(id: Long): Boolean {
        val node = getNode(id)
        if (node == null || node.builtin) return false
        writableDatabase.delete("nodes", "id=?", arrayOf(id.toString()))
        return true
    }

    fun insertResult(result: TestResult): Long {
        val cv = ContentValues().apply {
            put("network_id", result.networkId)
            put("node_id", result.nodeId)
            put("timestamp", result.timestamp)
            put("download_mbps", result.downloadMbps)
            put("upload_mbps", result.uploadMbps)
            put("latency_ms", result.latencyMs)
            put("jitter_ms", result.jitterMs)
            put("packet_loss", result.packetLoss)
            put("rssi", result.rssi)
            put("band", result.band)
            put("link_speed_mbps", result.linkSpeedMbps)
            put("server_name", result.serverName)
            result.gatewayLatencyMs?.let { put("gateway_latency_ms", it) }
            result.gatewayJitterMs?.let { put("gateway_jitter_ms", it) }
        }
        return writableDatabase.insert("results", null, cv)
    }

    fun getResultsForNetwork(networkId: Long, limit: Int = 100): List<TestResult> {
        readableDatabase.query(
            "results",
            null,
            "network_id=?",
            arrayOf(networkId.toString()),
            null,
            null,
            "timestamp DESC",
            limit.toString()
        ).use { c ->
            val list = mutableListOf<TestResult>()
            while (c.moveToNext()) {
                list.add(toResult(c))
            }
            return list
        }
    }

    fun getResultsForNetworks(networkIds: List<Long>): List<TestResult> {
        if (networkIds.isEmpty()) return emptyList()
        val placeholders = networkIds.joinToString(",") { "?" }
        val args = networkIds.map { it.toString() }.toTypedArray()
        readableDatabase.query(
            "results",
            null,
            "network_id IN ($placeholders)",
            args,
            null,
            null,
            "timestamp ASC"
        ).use { c ->
            val list = mutableListOf<TestResult>()
            while (c.moveToNext()) {
                list.add(toResult(c))
            }
            return list
        }
    }

    fun getAllResults(): List<TestResult> {
        readableDatabase.query("results", null, null, null, null, null, "timestamp ASC").use { c ->
            val list = mutableListOf<TestResult>()
            while (c.moveToNext()) {
                list.add(toResult(c))
            }
            return list
        }
    }

    fun getResultCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM results", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun resultExists(networkId: Long, nodeId: Long, timestamp: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM results WHERE network_id=? AND node_id=? AND timestamp=?",
            arrayOf(networkId.toString(), nodeId.toString(), timestamp.toString())
        ).use { c ->
            return c.moveToFirst() && c.getInt(0) > 0
        }
    }

    fun deleteResultsOlderThan(cutoffTimestamp: Long): Int {
        return writableDatabase.delete("results", "timestamp<?", arrayOf(cutoffTimestamp.toString()))
    }
}
