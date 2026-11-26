package com.shizuku.speedtest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shizuku.speedtest.data.AppDatabase
import com.shizuku.speedtest.data.KnownNetwork
import com.shizuku.speedtest.data.SettingsStore
import com.shizuku.speedtest.data.SpeedNode
import com.shizuku.speedtest.data.TestResult
import com.shizuku.speedtest.data.ThemeMode
import com.shizuku.speedtest.data.TrafficTier
import com.shizuku.speedtest.net.CurrentNetwork
import com.shizuku.speedtest.net.SpeedTestEngine
import com.shizuku.speedtest.net.TestOutcome
import com.shizuku.speedtest.net.TestPhase
import com.shizuku.speedtest.net.getCurrentNetwork
import com.shizuku.speedtest.net.hasWifiPermission
import com.shizuku.speedtest.net.isLocationEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface TestUiState {
    data object Idle : TestUiState

    data class Running(
        val phase: TestPhase,
        val liveValue: Double,
        val phaseSamples: List<Double>
    ) : TestUiState

    data class Done(
        val outcome: TestOutcome,
        val result: TestResult
    ) : TestUiState

    data class Error(
        val message: String
    ) : TestUiState
}

data class WifiPermissionState(
    val permissionGranted: Boolean = false,
    val locationEnabled: Boolean = false
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    data class RestoreSummary(
        val networks: Int,
        val nodes: Int,
        val results: Int,
        val skipped: Int
    )

    private val db = AppDatabase(application)
    private val settings = SettingsStore(application)
    private val engine = SpeedTestEngine()

    private val _currentNetworkInfo = MutableStateFlow<CurrentNetwork?>(null)
    val currentNetworkInfo: StateFlow<CurrentNetwork?> = _currentNetworkInfo.asStateFlow()

    private val _currentNetwork = MutableStateFlow<KnownNetwork?>(null)
    val currentNetwork: StateFlow<KnownNetwork?> = _currentNetwork.asStateFlow()

    private val _networks = MutableStateFlow<List<KnownNetwork>>(emptyList())
    val networks: StateFlow<List<KnownNetwork>> = _networks.asStateFlow()

    private val _nodes = MutableStateFlow<List<SpeedNode>>(emptyList())
    val nodes: StateFlow<List<SpeedNode>> = _nodes.asStateFlow()

    private val _selectedNodeId = MutableStateFlow(-1L)
    val selectedNodeId: StateFlow<Long> = _selectedNodeId.asStateFlow()

    private val _tier = MutableStateFlow(settings.trafficTier)
    val tier: StateFlow<TrafficTier> = _tier.asStateFlow()

    private val _testState = MutableStateFlow<TestUiState>(TestUiState.Idle)
    val testState: StateFlow<TestUiState> = _testState.asStateFlow()

    private val _recentResults = MutableStateFlow<List<TestResult>>(emptyList())
    val recentResults: StateFlow<List<TestResult>> = _recentResults.asStateFlow()

    private val _allResults = MutableStateFlow<List<TestResult>>(emptyList())
    val allResults: StateFlow<List<TestResult>> = _allResults.asStateFlow()

    private val _retentionDays = MutableStateFlow(settings.retentionDays)
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(settings.dynamicColor)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _wifiPermissionState = MutableStateFlow(WifiPermissionState())
    val wifiPermissionState: StateFlow<WifiPermissionState> = _wifiPermissionState.asStateFlow()

    private val _resultCount = MutableStateFlow(0)
    val resultCount: StateFlow<Int> = _resultCount.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            cleanupOldResults()
            refreshNodes()
            refreshNetworks()
            refreshResultCount()
            withContext(Dispatchers.Main) {
                refreshWifi()
            }
        }
    }

    private fun refreshNodes() {
        _nodes.value = db.getNodes()
    }

    private fun refreshNetworks() {
        _networks.value = db.getNetworks()
    }

    private fun refreshResultCount() {
        _resultCount.value = db.getResultCount()
    }

    private fun cleanupOldResults(): Int {
        val days = settings.retentionDays
        if (days < 0) return 0
        val cutoff = System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        return db.deleteResultsOlderThan(cutoff)
    }

    fun refreshWifi() {
        if (_testState.value is TestUiState.Running) return
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _wifiPermissionState.value = WifiPermissionState(
                permissionGranted = hasWifiPermission(app),
                locationEnabled = isLocationEnabled(app)
            )
            val info = getCurrentNetwork(app)
            _currentNetworkInfo.value = info
            if (info != null) {
                var net = db.getOrCreateNetwork(info.ssid, info.bssid)
                if (info.isWifi && info.ssidKnown && info.gatewayIp != null) {
                    val gwNet = db.getNetworkByIdentity("网关 ${info.gatewayIp}", info.gatewayIp)
                    if (gwNet != null && gwNet.id != net.id) {
                        val moved = db.mergeNetwork(gwNet.id, net.id)
                        if (moved > 0) {
                            net = db.getNetwork(net.id) ?: net
                            _message.tryEmit("已把 $moved 条旧记录合并到 ${net.displayName}")
                        }
                    }
                }
                _currentNetwork.value = net

                val preferredId = net.defaultNodeId ?: settings.lastNodeId
                val nodes = db.getNodes()
                val matched = nodes.firstOrNull { it.id == preferredId } ?: nodes.firstOrNull()
                if (matched != null) {
                    _selectedNodeId.value = matched.id
                }
                _recentResults.value = db.getResultsForNetwork(net.id, 10)
                refreshNetworks()
            }
        }
    }

    fun selectNode(nodeId: Long) {
        _selectedNodeId.value = nodeId
        settings.lastNodeId = nodeId
    }

    fun setTier(tier: TrafficTier) {
        _tier.value = tier
        settings.trafficTier = tier
    }

    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _themeMode.value = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        settings.dynamicColor = enabled
        _dynamicColor.value = enabled
    }

    fun showMessage(text: String) {
        _message.tryEmit(text)
    }

    fun startTest() {
        if (testJob?.isActive == true) return
        val node = _nodes.value.firstOrNull { it.id == _selectedNodeId.value } ?: return
        val info = _currentNetworkInfo.value
        val network = _currentNetwork.value
        if (info == null || network == null) {
            _message.tryEmit("未检测到网络连接")
            return
        }

        settings.lastNodeId = node.id
        _testState.value = TestUiState.Running(TestPhase.LATENCY, 0.0, emptyList())

        val phaseSamples = mutableListOf<Double>()
        var currentPhase = TestPhase.LATENCY

        testJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = engine.run(node, _tier.value, info.gatewayIp) { sample ->
                    if (sample.phase != currentPhase) {
                        currentPhase = sample.phase
                        phaseSamples.clear()
                    }
                    if (sample.phase != TestPhase.LATENCY || sample.value != 0.0) {
                        phaseSamples.add(sample.value)
                    }
                    _testState.value = TestUiState.Running(
                        phase = sample.phase,
                        liveValue = sample.value,
                        phaseSamples = phaseSamples.toList()
                    )
                }

                val result = TestResult(
                    networkId = network.id,
                    nodeId = node.id,
                    timestamp = System.currentTimeMillis(),
                    downloadMbps = outcome.downloadMbps,
                    uploadMbps = outcome.uploadMbps,
                    latencyMs = outcome.latencyMs,
                    jitterMs = outcome.jitterMs,
                    packetLoss = outcome.packetLoss,
                    rssi = info.rssi,
                    band = info.band,
                    linkSpeedMbps = info.linkSpeedMbps,
                    serverName = node.name,
                    gatewayLatencyMs = outcome.gatewayLatencyMs,
                    gatewayJitterMs = outcome.gatewayJitterMs
                )
                val insertedId = db.insertResult(result)
                cleanupOldResults()
                _recentResults.value = db.getResultsForNetwork(network.id, 10)
                refreshResultCount()
                _testState.value = TestUiState.Done(outcome, result.copy(id = insertedId))
            } catch (e: CancellationException) {
                _testState.value = TestUiState.Idle
                throw e
            } catch (e: Exception) {
                _testState.value = TestUiState.Error(e.message ?: "测速失败")
            }
        }
    }

    fun cancelTest() {
        engine.cancel()
        testJob?.cancel()
        _testState.value = TestUiState.Idle
    }

    fun resetTestState() {
        if (_testState.value is TestUiState.Running) return
        _testState.value = TestUiState.Idle
    }

    fun addNode(
        name: String,
        baseUrl: String,
        protocol: String,
        onDone: (Long) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = db.insertNode(
                SpeedNode(
                    name = name,
                    baseUrl = baseUrl,
                    protocol = protocol,
                    builtin = false
                )
            )
            refreshNodes()
            withContext(Dispatchers.Main) {
                onDone(id)
            }
        }
    }

    fun updateNode(node: SpeedNode) {
        viewModelScope.launch(Dispatchers.IO) {
            db.updateNode(node)
            refreshNodes()
        }
    }

    fun deleteNode(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = db.deleteNode(id)
            refreshNodes()
            if (deleted) {
                if (_selectedNodeId.value == id) {
                    _selectedNodeId.value = _nodes.value.firstOrNull()?.id ?: -1L
                }
                _message.tryEmit("节点已删除")
            } else {
                _message.tryEmit("内置节点不能删除")
            }
        }
    }

    suspend fun probeNode(node: SpeedNode): Double? = SpeedTestEngine.probeNode(node)

    fun setNetworkAlias(networkId: Long, alias: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.updateNetworkAlias(networkId, alias)
            refreshNetworks()
            if (_currentNetwork.value?.id == networkId) {
                _currentNetwork.value = db.getNetwork(networkId)
            }
        }
    }

    fun setNetworkDefaultNode(networkId: Long, nodeId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            db.updateNetworkDefaultNode(networkId, nodeId)
            refreshNetworks()
            if (_currentNetwork.value?.id == networkId) {
                _currentNetwork.value = db.getNetwork(networkId)
                if (nodeId != null) {
                    _selectedNodeId.value = nodeId
                }
            }
        }
    }

    fun setRetentionDays(days: Int) {
        settings.retentionDays = days
        _retentionDays.value = days
        viewModelScope.launch(Dispatchers.IO) {
            val removed = cleanupOldResults()
            refreshResultCount()
            if (removed > 0) {
                _message.tryEmit("已清理 $removed 条过期记录")
                val cur = _currentNetwork.value
                _recentResults.value = if (cur != null) db.getResultsForNetwork(cur.id, 10) else emptyList()
                _allResults.value = db.getAllResults()
            }
        }
    }

    fun refreshCompareData() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshNetworks()
            _allResults.value = db.getAllResults()
        }
    }

    private fun csvCell(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    suspend fun buildCsv(): String = withContext(Dispatchers.IO) {
        val results = db.getAllResults()
        val netMap = db.getNetworks().associateBy { it.id }
        val nodeMap = db.getNodes().associateBy { it.id }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder(
            "时间,网络,节点,下载(Mbps),上传(Mbps),延迟(ms),抖动(ms),丢包率,信号(dBm),频段,协商速率(Mbps),网关延迟(ms),网关抖动(ms)\n"
        )
        for (r in results) {
            val netName = netMap[r.networkId]?.displayName ?: ""
            val nodeName = nodeMap[r.nodeId]?.name ?: r.serverName
            sb.append(sdf.format(Date(r.timestamp)))
            sb.append(",")
            sb.append(csvCell(netName))
            sb.append(",")
            sb.append(csvCell(nodeName))
            sb.append(",")
            sb.append(String.format(Locale.US, "%.2f", r.downloadMbps))
            sb.append(",")
            sb.append(String.format(Locale.US, "%.2f", r.uploadMbps))
            sb.append(",")
            sb.append(String.format(Locale.US, "%.1f", r.latencyMs))
            sb.append(",")
            sb.append(String.format(Locale.US, "%.1f", r.jitterMs))
            sb.append(",")
            sb.append(String.format(Locale.US, "%.2f", r.packetLoss))
            sb.append(",")
            sb.append(r.rssi)
            sb.append(",")
            sb.append(r.band)
            sb.append(",")
            sb.append(r.linkSpeedMbps)
            sb.append(",")
            sb.append(r.gatewayLatencyMs?.let { String.format(Locale.US, "%.1f", it) } ?: "")
            sb.append(",")
            sb.append(r.gatewayJitterMs?.let { String.format(Locale.US, "%.1f", it) } ?: "")
            sb.append("\n")
        }
        sb.toString()
    }

    suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) {
        val networks = db.getNetworks()
        val nodes = db.getNodes()
        val results = db.getAllResults()
        val netMap = networks.associateBy { it.id }
        val nodeMap = nodes.associateBy { it.id }

        val root = JSONObject()
        root.put("app", "shizuku-speedtest")
        root.put("version", 1)
        root.put("exported_at", System.currentTimeMillis())

        val netArr = JSONArray()
        for (n in networks) {
            val obj = JSONObject()
            obj.put("ssid", n.ssid)
            obj.put("bssid", n.bssid)
            obj.put("alias", n.alias)
            n.defaultNodeId?.let { id ->
                nodeMap[id]?.baseUrl?.let { url ->
                    obj.put("default_node_url", url)
                }
            }
            netArr.put(obj)
        }
        root.put("networks", netArr)

        val nodeArr = JSONArray()
        for (node in nodes) {
            val obj = JSONObject()
            obj.put("name", node.name)
            obj.put("base_url", node.baseUrl)
            obj.put("protocol", node.protocol)
            obj.put("builtin", node.builtin)
            nodeArr.put(obj)
        }
        root.put("nodes", nodeArr)

        val resArr = JSONArray()
        for (r in results) {
            val net = netMap[r.networkId] ?: continue
            val node = nodeMap[r.nodeId]
            val obj = JSONObject()
            obj.put("ssid", net.ssid)
            obj.put("bssid", net.bssid)
            obj.put("node_url", node?.baseUrl ?: "")
            obj.put("server_name", r.serverName)
            obj.put("timestamp", r.timestamp)
            obj.put("download_mbps", r.downloadMbps)
            obj.put("upload_mbps", r.uploadMbps)
            obj.put("latency_ms", r.latencyMs)
            obj.put("jitter_ms", r.jitterMs)
            obj.put("packet_loss", r.packetLoss)
            obj.put("rssi", r.rssi)
            obj.put("band", r.band)
            obj.put("link_speed_mbps", r.linkSpeedMbps)
            r.gatewayLatencyMs?.let { obj.put("gateway_latency_ms", it) }
            r.gatewayJitterMs?.let { obj.put("gateway_jitter_ms", it) }
            resArr.put(obj)
        }
        root.put("results", resArr)
        root.toString(2)
    }

    suspend fun restoreBackupJson(json: String): RestoreSummary = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        if (root.optString("app") != "shizuku-speedtest") {
            throw IllegalArgumentException("不是 Shizuku测速 的备份文件")
        }

        val nodeUrlToId = linkedMapOf<String, Long>()
        val nodesArr = root.optJSONArray("nodes") ?: JSONArray()
        var addedNodes = 0
        for (i in 0 until nodesArr.length()) {
            val obj = nodesArr.getJSONObject(i)
            val baseUrl = obj.getString("base_url").trimEnd('/')
            val existing = db.getNodeByUrl(baseUrl)
            if (existing != null) {
                nodeUrlToId[baseUrl] = existing.id
            } else {
                val id = db.insertNode(
                    SpeedNode(
                        name = obj.optString("name", baseUrl),
                        baseUrl = baseUrl,
                        protocol = obj.optString("protocol", SpeedNode.PROTOCOL_CLOUDFLARE),
                        builtin = obj.optBoolean("builtin", false)
                    )
                )
                nodeUrlToId[baseUrl] = id
                addedNodes++
            }
        }

        val netKeyToId = linkedMapOf<String, Long>()
        val netsArr = root.optJSONArray("networks") ?: JSONArray()
        var addedNetworks = 0
        for (i in 0 until netsArr.length()) {
            val obj = netsArr.getJSONObject(i)
            val ssid = obj.getString("ssid")
            val bssid = obj.optString("bssid", "")
            val existed = db.getNetworkByIdentity(ssid, bssid)
            val net = db.getOrCreateNetwork(ssid, bssid)
            netKeyToId["$ssid\u0000$bssid"] = net.id
            if (existed == null) addedNetworks++

            val alias = obj.optString("alias", "")
            if (net.alias.isBlank() && alias.isNotBlank()) {
                db.updateNetworkAlias(net.id, alias)
            }
            val defaultNodeUrl = obj.optString("default_node_url", "").trimEnd('/')
            if (net.defaultNodeId == null && defaultNodeUrl.isNotEmpty()) {
                nodeUrlToId[defaultNodeUrl]?.let { nid ->
                    db.updateNetworkDefaultNode(net.id, nid)
                }
            }
        }

        val resArr = root.optJSONArray("results") ?: JSONArray()
        var addedResults = 0
        var skippedResults = 0
        for (i in 0 until resArr.length()) {
            val obj = resArr.getJSONObject(i)
            val ssid = obj.getString("ssid")
            val bssid = obj.optString("bssid", "")
            val key = "$ssid\u0000$bssid"
            val networkId = netKeyToId[key] ?: db.getOrCreateNetwork(ssid, bssid).also {
                netKeyToId[key] = it.id
            }.id

            val nodeUrl = obj.optString("node_url", "").trimEnd('/')
            val nodeId = nodeUrlToId[nodeUrl] ?: db.getNodes().firstOrNull()?.id ?: continue

            val timestamp = obj.getLong("timestamp")
            if (db.resultExists(networkId, nodeId, timestamp)) {
                skippedResults++
            } else {
                db.insertResult(
                    TestResult(
                        networkId = networkId,
                        nodeId = nodeId,
                        timestamp = timestamp,
                        downloadMbps = obj.optDouble("download_mbps"),
                        uploadMbps = obj.optDouble("upload_mbps"),
                        latencyMs = obj.optDouble("latency_ms"),
                        jitterMs = obj.optDouble("jitter_ms"),
                        packetLoss = obj.optDouble("packet_loss"),
                        rssi = obj.optInt("rssi"),
                        band = obj.optString("band", ""),
                        linkSpeedMbps = obj.optInt("link_speed_mbps"),
                        serverName = obj.optString("server_name", ""),
                        gatewayLatencyMs = if (obj.has("gateway_latency_ms")) obj.optDouble("gateway_latency_ms") else null,
                        gatewayJitterMs = if (obj.has("gateway_jitter_ms")) obj.optDouble("gateway_jitter_ms") else null
                    )
                )
                addedResults++
            }
        }

        refreshNodes()
        refreshNetworks()
        refreshResultCount()
        val cur = _currentNetwork.value
        _recentResults.value = if (cur != null) db.getResultsForNetwork(cur.id, 10) else emptyList()
        _allResults.value = db.getAllResults()

        RestoreSummary(
            networks = addedNetworks,
            nodes = addedNodes,
            results = addedResults,
            skipped = skippedResults
        )
    }
}
