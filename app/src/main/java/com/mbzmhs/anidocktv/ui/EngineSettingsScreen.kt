package com.mbzmhs.anidocktv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.data.AppProxy
import com.mbzmhs.anidocktv.data.EngineService
import com.mbzmhs.anidocktv.ui.theme.Anidock
import com.mbzmhs.anidocktv.ui.tv.ImeMode
import com.mbzmhs.anidocktv.ui.tv.K
import com.mbzmhs.anidocktv.ui.tv.KeyHit
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 引擎设置页(设置 → 下载引擎):对齐 ZDownloader web 版设置,TV 相关子集。
 * - 数字项:系统输入法编辑,键盘「完成/回车」保存;
 * - 开关项:回车直接切换,立即生效;
 * - 保存 = PUT /api/config 部分更新(引擎热生效:限速/并发/做种/DHT/PEX/坏块封禁)。
 * 引擎未运行时进入页面先自动拉起再读配置。
 */
@Composable
fun EngineSettingsScreen(onBack: () -> Unit, keyHit: KeyHit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var rowIdx by remember { mutableStateOf(0) }
    var cfg by remember { mutableStateOf<EngineService.EngineConfig?>(null) }
    var loadError by remember { mutableStateOf("") }
    var textEdit by remember { mutableStateOf<TextEditSpec?>(null) }
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var sysProxyText by remember { mutableStateOf("") }
    var banOpen by remember { mutableStateOf(false) }
    var bans by remember { mutableStateOf<List<EngineService.BanEntry>?>(null) }
    var banIdx by remember { mutableStateOf(0) }
    var banCount by remember { mutableStateOf(0) }
    var trackersEdit by remember { mutableStateOf(false) }
    var trackersConfirm by remember { mutableStateOf<Int?>(null) } // null=编辑中, 0/1=确认弹层选中
    val visH = remember { mutableStateOf(0) }
    val scroll = rememberScrollState()
    val initialSeq = remember { keyHit.seq }

    // 进入页面:先确保引擎在运行,再读配置
    LaunchedEffect(Unit) {
        val up = EngineService.ensureRunning(context)
        val c = EngineService.getEngineConfig()
        cfg = c
        if (c == null) loadError = if (up) "读取引擎配置失败" else "下载引擎启动失败,无法读取配置"
        else loadError = ""
        sysProxyText = formatSysProxy(EngineService.getSysProxy())
        banCount = EngineService.getBans()?.size ?: 0
    }
    DisposableEffect(Unit) { onDispose { ImeMode.active = false; (scope.coroutineContext[kotlinx.coroutines.Job] as? kotlinx.coroutines.Job)?.cancel() } }
    LaunchedEffect(statusMsg) {
        if (statusMsg != null) {
            kotlinx.coroutines.delay(3000)
            statusMsg = null
        }
    }

    BackHandler { onBack() }
    BackHandler(enabled = textEdit != null) {
        ImeMode.active = false
        textEdit = null
        pendingEdit = null
    }
    BackHandler(enabled = banOpen) {
        banOpen = false
    }
    BackHandler(enabled = trackersEdit && trackersConfirm == null) {
        // BACK:关 IME + 弹"保存?"确认
        ImeMode.active = false
        trackersConfirm = 0
    }
    BackHandler(enabled = trackersEdit && trackersConfirm != null) {
        // 确认弹层 BACK:取消确认,回到编辑(重新开 IME)
        trackersConfirm = null
        ImeMode.active = true
    }
    BackHandler(enabled = !trackersEdit && textEdit == null && !banOpen) {
        onBack()
    }

    fun openNumber(label: String, initial: String, decimal: Boolean, validate: (String) -> String?, apply: (String) -> JsonObject) {
        textEdit = TextEditSpec("number", label, initial, false, !decimal, decimal)
        pendingEdit = PendingEdit(label, validate, apply)
        ImeMode.active = true
    }

    /** 部分更新引擎配置并回读;失败给提示。
     *  保存成功后同步 app 层代理:mode/url 真变了 → AppProxy.sync 踢连接池+发刷新信号,
     *  常驻首页自动重登+重拉,热切换不重启。 */
    fun applyPatch(patch: JsonObject, label: String) {
        scope.launch {
            val ok = EngineService.saveEngineConfig(patch)
            if (ok) {
                kotlinx.coroutines.delay(500) // 等引擎落盘/刷新内存
                val c2 = EngineService.getEngineConfig()
                cfg = c2
                c2?.let { AppProxy.sync(it, Services.settingDao) }
                statusMsg = "已保存:$label"
            } else {
                statusMsg = "保存失败:$label(引擎未运行)"
            }
        }
    }

    fun toggleBt(key: String, current: Boolean, label: String) {
        val v = !current
        val c = cfg ?: return
        // 乐观更新:立即本地刷新 UI
        val newBt = c.bt.copy(
            dht = if (key == "dht") v else c.bt.dht,
            pex = if (key == "pex") v else c.bt.pex,
            autoSeed = if (key == "autoSeed") v else c.bt.autoSeed,
            autoAddTrackers = if (key == "autoAddTrackers") v else c.bt.autoAddTrackers,
            autoBanPoison = if (key == "autoBanPoison") v else c.bt.autoBanPoison,
            spoofUA = if (key == "spoofUA") v else c.bt.spoofUA,
        )
        cfg = c.copy(bt = newBt)
        // 后台发引擎(完整 bt 对象,由数据类序列化,不逐字段枚举)
        scope.launch {
            val ok = EngineService.saveBtConfig(newBt)
            statusMsg = if (ok) "已保存:$label" else "保存失败:$label"
        }
    }

    /** 代理模式循环:none → manual → env → none。 */
    fun cycleProxyMode(current: String) {
        val next = when (current) { "none" -> "manual"; "manual" -> "env"; else -> "none" }
        applyPatch(buildJsonObject { put("proxy", buildJsonObject { put("mode", next) }) }, "代理模式:$next")
    }

    /** 编辑代理地址(文本,http(s):// 或 socks5://user:pass@host:port)。 */
    fun openProxyUrl(initial: String) {
        textEdit = TextEditSpec("url", "代理地址(http(s):// 或 socks5://user:pass@host:port)", initial, false, false)
        pendingEdit = PendingEdit("代理地址", { null }, { v -> buildJsonObject { put("proxy", buildJsonObject { put("url", v) }) } })
        ImeMode.active = true
    }

    /** 代理用途开关(仅 manual 模式生效)。 */
    fun toggleProxy(key: String, current: Boolean, label: String) {
        val v = !current
        applyPatch(buildJsonObject { put("proxy", buildJsonObject { put(key, v) }) }, label)
    }

    fun finishTextEdit(value: String?) {
        ImeMode.active = false
        val p = pendingEdit
        textEdit = null
        pendingEdit = null
        if (p == null || value == null) return
        val err = p.validate(value)
        if (err != null) {
            statusMsg = err
            return
        }
        applyPatch(p.apply(value), p.label)
    }

    /** 打开封禁名单弹层并拉取列表。 */
    fun openBans() {
        banIdx = 0
        bans = null
        banOpen = true
        scope.launch { bans = EngineService.getBans() }
    }

    fun handleBanKey(key: Key) {
        val list = bans ?: return   // 加载中不动
        val n = list.size + 1        // + 末行「清空全部」
        when (key) {
            K.UP -> banIdx = (banIdx + n - 1) % n
            K.DOWN -> banIdx = (banIdx + 1) % n
            K.CENTER, K.ENTER -> if (banIdx == list.size) {
                scope.launch {
                    if (EngineService.clearBans()) {
                        bans = emptyList()
                        banCount = 0
                        statusMsg = "已清空全部封禁"
                    } else statusMsg = "清空封禁失败"
                }
            } else {
                val ip = list[banIdx].ip
                scope.launch {
                    if (EngineService.unban(ip)) {
                        bans = EngineService.getBans()
                        banCount = bans?.size ?: 0
                        statusMsg = "已解除封禁:$ip"
                    } else statusMsg = "解除封禁失败:$ip"
                }
            }
        }
    }

    fun handleKey(key: Key) {
        if (textEdit != null) return   // IME 编辑中按键已放行给系统
        if (banOpen) {
            handleBanKey(key)
            return
        }
        val c = cfg ?: return
        when (key) {
            K.UP -> rowIdx = (rowIdx + ROW_N - 1) % ROW_N
            K.DOWN -> rowIdx = (rowIdx + 1) % ROW_N
            K.CENTER, K.ENTER -> when (rowIdx) {
                0 -> openNumber(
                    "全局下载限速(KB/s,0 = 不限速)", (c.globalLimit / 1024).toString(), false,
                    { v ->
                        val kb = v.toLongOrNull() ?: return@openNumber "请输入非负整数(KB/s)"
                        if (kb > 1_000_000) "范围 0 - 1000000 KB/s" else null
                    },
                    { v -> buildJsonObject { put("globalLimit", v.toLong() * 1024) } },
                )
                1 -> openNumber(
                    "全局上传限速(KB/s,0 = 不限速)", (c.bt.upSpeed / 1024).toString(), false,
                    { v ->
                        val kb = v.toLongOrNull() ?: return@openNumber "请输入非负整数(KB/s)"
                        if (kb > 1_000_000) "范围 0 - 1000000 KB/s" else null
                    },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("upSpeed", v.toLong() * 1024) }) } },
                )
                2 -> openNumber(
                    "同时下载任务数(1-10)", c.maxActiveTasks.toString(), false,
                    { v ->
                        val n = v.toIntOrNull() ?: return@openNumber "请输入整数"
                        if (n !in 1..10) "范围 1 - 10" else null
                    },
                    { v -> buildJsonObject { put("maxActiveTasks", v.toInt()) } },
                )
                3 -> openNumber(
                    "默认线程数(1-64)", c.maxConnections.toString(), false,
                    { v ->
                        val n = v.toIntOrNull() ?: return@openNumber "请输入整数"
                        if (n !in 1..64) "范围 1 - 64" else null
                    },
                    { v -> buildJsonObject { put("maxConnections", v.toInt()) } },
                )
                4 -> openNumber(
                    "单任务最大 peer 连接数(1-200)", c.bt.maxPeers.toString(), false,
                    { v ->
                        val n = v.toIntOrNull() ?: return@openNumber "请输入整数"
                        if (n !in 1..200) "范围 1 - 200" else null
                    },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("maxPeers", v.toInt()) }) } },
                )
                5 -> toggleBt("autoAddTrackers", c.bt.autoAddTrackers, "自动补充公共 tracker")
                6 -> toggleBt("dht", c.bt.dht, "使用 DHT")
                7 -> toggleBt("pex", c.bt.pex, "使用 PEX")
                8 -> toggleBt("autoSeed", c.bt.autoSeed, "完成后自动做种")
                9 -> openNumber(
                    "做种目标分享率(0 = 不按比率停)", c.bt.seedRatio.toString(), true,
                    { v ->
                        val r = v.toDoubleOrNull() ?: return@openNumber "请输入数字"
                        if (r < 0 || r > 100) "范围 0 - 100" else null
                    },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("seedRatio", v.toDouble()) }) } },
                )
                10 -> openNumber(
                    "最短做种时长(分钟,0 = 不按时长停)", c.bt.seedMinutes.toString(), false,
                    { v ->
                        val n = v.toIntOrNull() ?: return@openNumber "请输入整数"
                        if (n !in 0..100000) "范围 0 - 100000 分钟" else null
                    },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("seedMinutes", v.toInt()) }) } },
                )
                11 -> toggleBt("autoBanPoison", c.bt.autoBanPoison, "自动封禁 PCDN 客户端")
                12 -> openNumber(
                    "异常分片豁免阈值(50-100)", c.bt.poisonRate.toString(), false,
                    { v ->
                        val n = v.toIntOrNull() ?: return@openNumber "请输入整数"
                        if (n !in 50..100) "范围 50 - 100" else null
                    },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("poisonRate", v.toInt()) }) } },
                )
                14 -> openBans()
                13 -> openNumber(
                    "异常分片累计上限(1-10 次)", c.bt.poisonStrikes.toString(), false,
                    { v ->
                        val n = v.toIntOrNull() ?: return@openNumber "请输入整数"
                        if (n !in 1..10) "范围 1 - 10" else null
                    },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("poisonStrikes", v.toInt()) }) } },
                )
                15 -> cycleProxyMode(c.proxy.mode)
                // 16 = 跟随系统检测(只读回显,不做事)
                // 空地址预填本地 socks5,减少电视端输入
                17 -> openProxyUrl(c.proxy.url.ifBlank { "socks5://127.0.0.1:10808" })
                18 -> toggleProxy("forHTTP", c.proxy.forHTTP, "HTTP 下载走代理")
                19 -> toggleProxy("forTracker", c.proxy.forTracker, "BT tracker 走代理")
                20 -> toggleProxy("forPeer", c.proxy.forPeer, "BT peer 走代理")
                21 -> toggleProxy("forWebSeed", c.proxy.forWebSeed, "WebSeed 走代理")
                22 -> toggleProxy("dohDNS", c.proxy.dohDNS, "DNS 走代理(DoH)")
                23 -> { trackersEdit = true; ImeMode.active = true }
                24 -> toggleBt("spoofUA", c.bt.spoofUA, "伪装 BT 客户端 ID")
                25 -> openNumber(
                    "伪装名称(如 BitComet 1.99)", c.bt.spoofName.ifBlank { "BitComet 1.99" }, false,
                    { v -> null },
                    { v -> buildJsonObject { put("bt", buildJsonObject { put("spoofName", v) }) } },
                )
            }
        }
    }

    LaunchedEffect(keyHit.seq) {
        if (keyHit.seq > initialSeq) handleKey(keyHit.key)
    }

    val c = cfg
    // 设置项按逻辑分组(分区标题 + 设置项交错);可聚焦项下标 0-25 保持不变,
    // 供 handleKey 的 when(rowIdx) 沿用;分组标题只是视觉层级,不参与按键分发。
    // 开关项用 toggle 字段渲染真滑块(value 留空被忽略)。
    val display = listOf(
        SettingDisplay.Section("限速与并发"),
        SettingDisplay.Item(SettingRowDef("全局下载限速", limitText(c?.globalLimit ?: 0), "回车编辑")),
        SettingDisplay.Item(SettingRowDef("全局上传限速", limitText(c?.bt?.upSpeed ?: 0), "回车编辑")),
        SettingDisplay.Item(SettingRowDef("同时下载任务数", c?.let { "${it.maxActiveTasks} (1-10)" } ?: "", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("默认线程数", c?.let { "${it.maxConnections} (1-64)" } ?: "", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("单任务最大 peer", c?.let { "${it.bt.maxPeers} (1-200)" } ?: "", "回车编辑")),
        SettingDisplay.Section("BT 网络"),
        SettingDisplay.Item(SettingRowDef("自动补充公共 tracker", "", "回车切换", toggle = c?.bt?.autoAddTrackers)),
        SettingDisplay.Item(SettingRowDef("使用 DHT(peer 发现)", "", "回车切换", toggle = c?.bt?.dht)),
        SettingDisplay.Item(SettingRowDef("使用 PEX(peer 交换)", "", "回车切换", toggle = c?.bt?.pex)),
        SettingDisplay.Section("做种"),
        SettingDisplay.Item(SettingRowDef("完成后自动做种", "", "回车切换", toggle = c?.bt?.autoSeed)),
        SettingDisplay.Item(SettingRowDef("做种目标分享率", c?.let { if (it.bt.seedRatio == 0.0) "0(不按比率停)" else it.bt.seedRatio.toString() } ?: "", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("最短做种时长", c?.let { if (it.bt.seedMinutes == 0) "0 分钟(不按时长停)" else "${it.bt.seedMinutes} 分钟" } ?: "", "回车编辑")),
        SettingDisplay.Section("封禁"),
        SettingDisplay.Item(SettingRowDef("自动封禁 PCDN 客户端", "", "回车切换", toggle = c?.bt?.autoBanPoison)),
        SettingDisplay.Item(SettingRowDef("异常分片豁免阈值", c?.let { "${it.bt.poisonRate}% (50-100)" } ?: "", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("异常分片累计上限", c?.let { "${it.bt.poisonStrikes} 次 (1-10)" } ?: "", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("封禁名单", if (banCount == 0) "无" else "${banCount} 个 IP", "回车打开 · 逐条解除或清空")),
        SettingDisplay.Section("代理"),
        SettingDisplay.Item(SettingRowDef("代理模式", proxyModeText(c?.proxy?.mode), "回车切换")),
        SettingDisplay.Item(SettingRowDef("跟随系统检测", sysProxyText.ifBlank { "—" }, "只读 · 系统代理模式下实际经代理的地址")),
        SettingDisplay.Item(SettingRowDef("代理地址", c?.proxy?.url?.takeIf { it.isNotBlank() } ?: "—", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("HTTP 下载走代理", "", "回车切换", toggle = c?.proxy?.forHTTP)),
        SettingDisplay.Item(SettingRowDef("BT tracker 走代理", "", "回车切换", toggle = c?.proxy?.forTracker)),
        SettingDisplay.Item(SettingRowDef("BT peer 走代理", "", "回车切换", toggle = c?.proxy?.forPeer)),
        SettingDisplay.Item(SettingRowDef("WebSeed 走代理", "", "回车切换", toggle = c?.proxy?.forWebSeed)),
        SettingDisplay.Item(SettingRowDef("DNS 走代理(DoH)", "", "回车切换", toggle = c?.proxy?.dohDNS)),
        SettingDisplay.Section("伪装与 Tracker"),
        SettingDisplay.Item(SettingRowDef("公共 Tracker 列表", c?.bt?.publicTrackers?.let { if (it.isEmpty()) "空" else "${it.size} 条" } ?: "—", "回车编辑(每行一条)")),
        SettingDisplay.Item(SettingRowDef("伪装 BT 客户端 ID", "", "回车切换", toggle = c?.bt?.spoofUA)),
        SettingDisplay.Item(SettingRowDef("伪装名称", c?.bt?.spoofName?.ifBlank { "—" } ?: "—", "回车编辑")),
    )
    // 分区标题占高,按 display 逐段累加像素,算出每个可聚焦行真实的 top(px)。
    val fieldTops = remember(display) {
        val gap = with(density) { 12.dp.roundToPx() }
        val headerH = with(density) { 30.dp.roundToPx() }
        val fieldH = with(density) { 56.dp.roundToPx() }
        val tops = ArrayList<Int>()
        var y = 0
        for (child in display) {
            if (child is SettingDisplay.Item) { tops.add(y); y += fieldH } else { y += headerH }
            y += gap
        }
        tops
    }
    // 每个 display 元素对应的"可聚焦行下标"(分区标题 = -1),渲染时判断哪一行获焦。
    val focusableIndex = remember(display) {
        var i = 0
        display.map { if (it is SettingDisplay.Item) i++ else -1 }
    }
    LaunchedEffect(rowIdx, visH.value, fieldTops) {
        val vh = visH.value
        if (vh <= 0) return@LaunchedEffect
        val fieldH = with(density) { 56.dp.roundToPx() }
        val top = fieldTops.getOrElse(rowIdx) { 0 }
        val cur = scroll.value
        val target = when {
            top < cur -> top
            top + fieldH > cur + vh -> (top + fieldH - vh).coerceAtLeast(0)
            else -> cur
        }
        if (target != cur) scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Anidock.Bg)
            .padding(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 20.dp),
    ) {
        if (textEdit == null) {
            Text("引擎设置", color = Anidock.Text, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    c != null -> "ZDownloader headless(随 app 自启)"
                    loadError.isNotEmpty() -> loadError
                    else -> "正在启动引擎并读取配置…"
                },
                color = if (loadError.isNotEmpty()) Anidock.Err else Anidock.Text3,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { visH.value = it.height }
                    .verticalScroll(scroll),
            ) {
                if (c == null) {
                    Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (loadError.isNotEmpty()) loadError else "加载中…",
                            color = if (loadError.isNotEmpty()) Anidock.Err else Anidock.Text3,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    display.forEachIndexed { i, child ->
                        when (child) {
                            is SettingDisplay.Section -> SectionHeader(child.title)
                            is SettingDisplay.Item -> SettingRowView(child.def, focused = focusableIndex[i] == rowIdx)
                        }
                    }
                }
            }
        }
        if (statusMsg != null) {
            Text(statusMsg!!, color = Anidock.Accent, style = MaterialTheme.typography.bodyMedium)
        }
    }

    textEdit?.let { spec ->
        TextEditOverlay(
            spec = spec,
            onDone = { v -> finishTextEdit(v) },
        )
    }

    // 公共 Tracker 多行编辑
    if (trackersEdit) {
        TrackersEditOverlay(
            initial = cfg?.bt?.publicTrackers?.joinToString("\n") ?: "",
            keyHit = keyHit,
            confirmIdx = trackersConfirm,
            onConfirmIdxChange = { trackersConfirm = it },
            onDone = { v ->
                ImeMode.active = false
                trackersEdit = false
                trackersConfirm = null
                val list = v.lines().map { it.trim() }.filter { it.isNotEmpty() }
                scope.launch {
                    val ok = EngineService.saveEngineConfig(buildJsonObject {
                        put("bt", buildJsonObject {
                            put(
                                "publicTrackers",
                                kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                            )
                        })
                    })
                    statusMsg = if (ok) "已保存 ${list.size} 条 tracker" else "保存失败"
                    cfg = EngineService.getEngineConfig()
                }
            },
            onCancel = {
                ImeMode.active = false
                trackersEdit = false
                trackersConfirm = null
            },
        )
    }

    // 封禁名单弹层:UP/DOWN 选行,ENTER 解除该行 / 清空全部,BACK 关闭
    if (banOpen) {
        val list = bans
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Anidock.Card,
                modifier = Modifier
                    .width(660.dp)
                    .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "封禁名单" + if (list != null && list.isNotEmpty()) " (${list.size})" else "",
                        color = Anidock.Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when {
                        list == null -> Text("加载中…", color = Anidock.Text3, style = MaterialTheme.typography.bodySmall)
                        list.isEmpty() -> Text("暂无封禁记录", color = Anidock.Text3, style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(340.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                list.forEachIndexed { i, b ->
                                    BanRowView(
                                        b,
                                        focused = i == banIdx,
                                        trailing = buildBanInfo(b),
                                    )
                                }
                                // 末行:清空全部
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (banIdx == list.size) Anidock.CardHover else Anidock.BgSoft,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .border(
                                            1.dp,
                                            if (banIdx == list.size) Anidock.Accent else Anidock.BorderSoft,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                ) {
                                    Text(
                                        "清空全部封禁 (${list.size} 条)",
                                        color = Anidock.Err,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                    Text("UP/DOWN 选择;ENTER = 解除封禁 / 清空全部;BACK = 关闭", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 封禁条目行(焦点态高亮)。 */
@Composable
private fun BanRowView(b: EngineService.BanEntry, focused: Boolean, trailing: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (focused) Anidock.CardHover else Anidock.BgSoft, RoundedCornerShape(8.dp))
            .border(1.dp, if (focused) Anidock.Accent else Anidock.BorderSoft, RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                b.ip + if (b.count > 1) " ×${b.count}" else "",
                color = Anidock.Text,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(trailing, color = Anidock.Text3, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 封禁条目附加信息:客户端名 · 原因。 */
private fun buildBanInfo(b: EngineService.BanEntry): String = listOf(
    b.client.takeIf { it.isNotBlank() },
    b.reason.takeIf { it.isNotBlank() },
).filterNotNull().joinToString(" · ").ifEmpty { "—" }

/** 待提交的编辑动作(数字或文本):校验通过才保存。 */
private class PendingEdit(val label: String, val validate: (String) -> String?, val apply: (String) -> JsonObject)

private const val ROW_N = 26

private fun limitText(bps: Long): String = when {
    bps <= 0 -> "不限速"
    bps >= 1048576 -> "%.1f MB/s".format(bps / 1048576.0)
    else -> "${bps / 1024} KB/s"
}

/** 代理模式文案(对齐 web:不使用代理 / 手动配置 / 跟随系统环境变量)。 */
private fun proxyModeText(mode: String?): String = when (mode) {
    "none" -> "不使用代理"
    "manual" -> "手动配置"
    "env" -> "跟随系统环境变量"
    else -> "—"
}

/** "跟随系统"检测结果回显:env 显示命中的环境变量+地址,系统代理显示地址,没检测到则直连。 */
private fun formatSysProxy(s: EngineService.SystemProxyStatus?): String {
    if (s == null) return ""
    val addr = s.url.ifBlank { s.addr }
    return when (s.source) {
        "" -> "未检测到(直连)"
        "env" -> "环境变量 ${s.envVar} = $addr"
        else -> "$addr"   // 平台系统代理(Windows 注册表 / macOS)
    }
}

/**
 * 公共 Tracker 多行编辑覆盖层:编辑后 BACK 退出时确认是否保存。
 * @param keyHit 按键事件(确认弹层用 LEFT/RIGHT 选,ENTER 确认)
 * @param confirmIdx 确认弹层选中项(0=保存,1=不保存),null=未弹确认
 */
@Composable
private fun TrackersEditOverlay(
    initial: String,
    keyHit: KeyHit,
    confirmIdx: Int?,
    onConfirmIdxChange: (Int?) -> Unit,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val initialSeq = remember { keyHit.seq }

    // 确认弹层按键:LEFT/RIGHT 切换,ENTER 确认
    LaunchedEffect(keyHit.seq) {
        if (keyHit.seq <= initialSeq) return@LaunchedEffect
        if (confirmIdx == null) return@LaunchedEffect
        when (keyHit.key) {
            K.LEFT -> onConfirmIdxChange(0)
            K.RIGHT -> onConfirmIdxChange(1)
            K.CENTER, K.ENTER -> {
                if (confirmIdx == 0) onDone(text) else onCancel()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .width(640.dp)
                .height(420.dp)
                .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("公共 Tracker 列表(每行一条)", color = Anidock.Text, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("编辑完成后按 BACK 退出,将询问是否保存", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
            }
        }
        // 确认弹层
        if (confirmIdx != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Anidock.Card,
                    modifier = Modifier
                        .width(400.dp)
                        .border(1.dp, Anidock.Border, RoundedCornerShape(12.dp)),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("保存修改?", color = Anidock.Text, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (confirmIdx == 0) Anidock.Accent.copy(alpha = 0.3f) else Anidock.Bg)
                                    .border(
                                        width = if (confirmIdx == 0) 2.dp else 1.dp,
                                        color = if (confirmIdx == 0) Anidock.Accent else Anidock.BorderSoft,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                            ) {
                                Text("保存", color = if (confirmIdx == 0) Color.White else Anidock.Text, style = MaterialTheme.typography.titleSmall)
                            }
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (confirmIdx == 1) Anidock.Accent.copy(alpha = 0.3f) else Anidock.Bg)
                                    .border(
                                        width = if (confirmIdx == 1) 2.dp else 1.dp,
                                        color = if (confirmIdx == 1) Anidock.Accent else Anidock.BorderSoft,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 24.dp, vertical = 10.dp),
                            ) {
                                Text("不保存", color = if (confirmIdx == 1) Color.White else Anidock.Text, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
