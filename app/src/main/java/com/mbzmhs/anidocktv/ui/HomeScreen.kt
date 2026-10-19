package com.mbzmhs.anidocktv.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.mbzmhs.anidocktv.data.AnimeCardData
import com.mbzmhs.anidocktv.data.AnimeEntity
import com.mbzmhs.anidocktv.data.DownloadTaskView
import com.mbzmhs.anidocktv.model.HomeFilters
import com.mbzmhs.anidocktv.ui.components.AnimeCard
import com.mbzmhs.anidocktv.ui.theme.Anidock
import com.mbzmhs.anidocktv.ui.tv.K
import com.mbzmhs.anidocktv.ui.tv.KeyHit

/** 首页标签。 */
enum class HomeTab { LIST, FOLLOWED }

/** 焦点区块。 */
private const val BLOCK_TAB = 0
private const val BLOCK_GRID = 1
private const val BLOCK_PAGER = 2

private const val COLS = 4

/** 记录某节点高度(px)。 */
private fun Modifier.trackHeight(block: (Int) -> Unit): Modifier =
    onSizeChanged { block(it.height) }

/**
 * 首页(焦点即状态):整个页面可滚动,顶部两个选择卡 + 方形封面网格 + 底部分页条。
 * Tab/网格/分页条都在滚动流里(不固定),焦点在 TAB→GRID→PAGER 间移动并带动滚动。
 * 侧边栏打开时焦点转移到侧边栏。按键由 MainActivity 根节点捕获后经 keyHit 分发。
 */
@Composable
fun HomeScreen(
    tab: HomeTab,
    /** 当前 tab 的筛选(两 tab 各一套,MainActivity 按 tab 传入);所有筛选/翻页改动经 onFilters 回写 */
    filters: HomeFilters,
    showSidebar: Boolean,
    sidebarMode: String,
    onSidebarMode: (String) -> Unit,
    /** 首页是否在最上层(未进详情/设置);false 时不处理按键,状态保留在后台 */
    active: Boolean,
    onTab: (HomeTab) -> Unit,
    onFilters: (HomeFilters) -> Unit,
    onAnimeClick: (AnimeEntity) -> Unit,
    onFollow: (AnimeEntity, Boolean) -> Unit,
    onSettings: () -> Unit,
    keyHit: KeyHit,
) {
    // 从当前 tab 的 filters 解出字段,便于下方沿用原变量名;翻页/侧边栏改动都经 onFilters(filters.copy(...)) 回写
    val year = filters.year
    val quarter = filters.quarter
    val page = filters.page
    val pageSize = filters.pageSize
    val hasEps = filters.hasEps
    val sort = filters.sort
    val search = filters.search
    var list by remember { mutableStateOf<List<AnimeCardData>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    // 侧边栏「下载任务」区:3s 轮询引擎进度,显示进行中+出错任务
    var dlTasks by remember { mutableStateOf<List<DownloadTaskView>>(emptyList()) }
    // 下载目录所在卷的剩余空间/占比,跟任务一起 3s 轮询刷新
    var diskInfo by remember { mutableStateOf(diskFreeLabel()) }
    // 任务操作菜单(CENTER 于任务行):暂停/继续、取消、重试(出错)、删除——与详情页同源同语义
    var taskMenu by remember { mutableStateOf<DownloadTaskView?>(null) }
    var taskMenuIdx by remember { mutableStateOf(0) }
    val taskScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            try {
                dlTasks = com.mbzmhs.anidocktv.Services.repo.activeDownloadTasks()
                diskInfo = diskFreeLabel()
                // 下载完成/进行中集数变化时,首页卡片的"已下载n集"胶囊随之刷新(纯本地查询;
                // 内容无变化时结构化等价比较不触发重绘)。详情页有独立的同款轮询
                if (list.isNotEmpty()) {
                    list = com.mbzmhs.anidocktv.Services.repo.enrichWithLocalMeta(list.map { it.anime })
                }
            } catch (_: Exception) {
            }
        }
    }
    // 任务菜单打开时 BACK 先关菜单
    if (taskMenu != null) BackHandler { taskMenu = null }

    /** 执行任务菜单动作:与详情页同一套 repo 方法,随后刷新列表。 */
    fun runTaskAction(t: DownloadTaskView, act: String) {
        taskScope.launch {
            val ok = when (act) {
                "暂停下载", "继续下载" -> com.mbzmhs.anidocktv.Services.repo.togglePauseDownload(t.task)
                "取消下载" -> {
                    com.mbzmhs.anidocktv.Services.repo.cancelDownload(t.task)
                    true
                }
                // 出错任务重试:优先 resume 引擎侧任务续传;引擎侧任务不存在则失败
                // (那种情况去详情页单击,会回退完整重下流程)
                "重试" -> runCatching {
                    com.mbzmhs.anidocktv.Services.repo.retryDownload(t.task.anidockId, t.task.episode)
                }.getOrDefault(false)
                "删除任务和文件" -> {
                    com.mbzmhs.anidocktv.Services.repo.deleteDownload(t.task)
                    true
                }
                else -> false
            }
            dlTasks = com.mbzmhs.anidocktv.Services.repo.activeDownloadTasks()
            if (!ok) android.widget.Toast.makeText(context, "操作失败,稍后再试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 启动焦点在 tab 栏,按默认首页落到对应 tab(LIST=0 / FOLLOWED=1),不直接落到第一部番剧
    var block by remember { mutableStateOf(BLOCK_TAB) }
    var col by remember { mutableStateOf(if (tab == HomeTab.FOLLOWED) 1 else 0) }
    var row by remember { mutableStateOf(0) }

    val scrollState = rememberScrollState()
    val tabH = remember { mutableStateOf(0) }
    val rowH = remember { mutableStateOf(0) }
    val visH = remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val tabGap = with(density) { 20.dp.roundToPx() }
    val rowGap = with(density) { 16.dp.roundToPx() }

    val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    // 侧边栏:主列表 6 行(年份/季度/每页/只看有剧集/排序/设置),聚焦年·季·每页·排序行按确认弹出组合框;「只看有剧集」为开关行
    // sidebarMode 由 MainActivity 持有(返回键需据它决定"退回主列表"还是"关闭侧边栏")
    var sidebarIdx by remember { mutableStateOf(0) }
    val mainItems = remember(filters) {
        val base = listOf(
            SideOption(label = "年份", sub = yearLabel(year)),
            SideOption(label = "季度", sub = quarterLabel(quarter)),
            SideOption(label = "每页数量", sub = "$pageSize 部"),
            SideOption(label = "只看有剧集", sub = if (hasEps) "开" else "关"),
            SideOption(label = "排序", sub = sortLabel(sort)),
            SideOption(label = "设置", sub = ""),
        )
        // 搜索词激活时末尾追加一行(不动现有 0-5 下标);点它清除搜索
        if (search.isNotBlank()) base + SideOption(label = "搜索", sub = search, selected = true, clear = true) else base
    }
    val pickerOptions = remember(sidebarMode, filters, curYear) {
        when (sidebarMode) {
            "year" -> (listOf(0) + (curYear downTo curYear - 12)).map { y -> SideOption(yearLabel(y), y, y == year) }
            "quarter" -> (0..4).map { q -> SideOption(quarterLabel(q), q, q == quarter) }
            "size" -> SIZE_OPTIONS.map { s -> SideOption("$s 部", s, s == pageSize) }
            "sort" -> listOf(
                SideOption("年度 / 季度", 0, sort == "season"),
                SideOption("最新更新", 1, sort == "latest"),
            )
            else -> emptyList()
        }
    }
    val pickerTitle = when (sidebarMode) {
        "year" -> "选择年份"
        "quarter" -> "选择季度"
        "size" -> "每页数量"
        "sort" -> "排序方式"
        else -> ""
    }
    // 组合框退回主列表(BACK 或确认选择)时,焦点落回刚离开的那一行,避免残留 picker 下标越界
    val prevMode = remember { mutableStateOf("main") }
    LaunchedEffect(sidebarMode) {
        if (sidebarMode == "main" && prevMode.value != "main") {
            sidebarIdx = mainIndexOf(prevMode.value)
        }
        prevMode.value = sidebarMode
    }

    LaunchedEffect(tab, filters) {
        loading = true
        try {
            com.mbzmhs.anidocktv.Services.repo.autoLogin()
            // 登录失败:提示用户去设置页检查密码/地址,不再继续请求
            val le = com.mbzmhs.anidocktv.Services.repo.loginError
            if (le.isNotEmpty()) {
                android.widget.Toast.makeText(context, "登录失败: $le\n请到设置页检查服务器地址/访客密码", android.widget.Toast.LENGTH_LONG).show()
                loading = false
                return@LaunchedEffect
            }
            if (tab == HomeTab.LIST) {
                // search 只作用于番剧列表(服务端匹配标题/别名/标签);追番 tab 是本地数据、无标签,不按搜索过滤
                val raw = com.mbzmhs.anidocktv.Services.repo.listPage(year, quarter, page, pageSize, hasEps, search, sort)
                total = com.mbzmhs.anidocktv.Services.repo.total(year, quarter, hasEps, search)
                list = com.mbzmhs.anidocktv.Services.repo.enrichWithLocalMeta(raw)
            } else {
                // 年份/季度/只看有剧集/排序 同样作用于追番 tab(本地过滤);打开 tab(page 回到 1)时用 ids 批量刷新一次,tab 内翻页纯本地
                if (page == 1) com.mbzmhs.anidocktv.Services.repo.refreshFollowed()
                val raw = com.mbzmhs.anidocktv.Services.repo.followed(page, pageSize, year, quarter, hasEps, sort)
                total = com.mbzmhs.anidocktv.Services.repo.followedCount(year, quarter, hasEps)
                list = com.mbzmhs.anidocktv.Services.repo.enrichWithLocalMeta(raw)
                android.util.Log.d("AnidockTV", "followed load: page=$page size=${list.size} total=$total")
            }
            // 焦点不抢:启动停在 tab 栏(便于切追番),列表加载只更新数据;仅空列表无网格可落时退回 tab 栏
            if (list.isEmpty()) { block = BLOCK_TAB; col = 0 }
        } catch (e: Exception) {
            android.util.Log.e("AnidockTV", "home page load fail", e)
        } finally {
            loading = false
        }
    }

    // 从详情页返回(active false→true)时只刷新封面胶囊(lastOpenedEpisode/downloadedCount 变了),
    // 不重拉 API 也不重置焦点(col/row 保持)。用已有实体重新合并本地数据即可。
    val prevActive = remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !prevActive.value && list.isNotEmpty()) {
            list = com.mbzmhs.anidocktv.Services.repo.enrichWithLocalMeta(list.map { it.anime })
        }
        prevActive.value = active
    }

    val pages = maxOf(1, total / pageSize)
    val rows = maxOf(1, (list.size + COLS - 1) / COLS)
    if (row >= rows) row = rows - 1
    // 最后一行不满时,焦点收拢到实际存在的卡片内,避免落到空格子上(翻页/筛选改变列表后同样兜底)。
    // 仅对网格生效:分页条的 col 是"左/右按钮"语义,不能被格子数收拢回去
    if (block == BLOCK_GRID) {
        if (list.isEmpty()) {
            col = 0
        } else {
            val inRow = minOf(COLS, list.size - row * COLS)   // 当前行实际卡片数
            if (col >= inRow) col = inRow - 1
            if (col < 0) col = 0
        }
    }

    fun handleKey(key: Key) {
        // 任务菜单打开时按键全归菜单,底层不响应(BACK 由 BackHandler 关闭)
        taskMenu?.let { m ->
            val acts = taskActions(m)
            when (key) {
                K.UP -> if (taskMenuIdx > 0) taskMenuIdx--
                K.DOWN -> if (taskMenuIdx < acts.size - 1) taskMenuIdx++
                K.CENTER, K.ENTER -> {
                    val act = acts.getOrNull(taskMenuIdx) ?: return@let
                    taskMenu = null
                    runTaskAction(m, act)
                }
                else -> {}
            }
            return
        }
        if (showSidebar) {
            if (sidebarMode == "main") {
                // 主行 6 项 + 进行中任务 N 项;任务列表实时变化,焦点下标越界时收拢
                val taskTotal = mainItems.size + dlTasks.size
                if (sidebarIdx >= taskTotal) sidebarIdx = (taskTotal - 1).coerceAtLeast(0)
                when (key) {
                    K.UP -> if (sidebarIdx > 0) sidebarIdx--
                    K.DOWN -> if (sidebarIdx < taskTotal - 1) sidebarIdx++
                    K.CENTER, K.ENTER -> when {
                        // 搜索行(仅激活时存在于末尾,即 mainItems.size-1):点它清除搜索词
                        search.isNotBlank() && sidebarIdx == mainItems.size - 1 -> onFilters(filters.copy(search = "", page = 1))
                        sidebarIdx < mainItems.size -> when (sidebarIdx) {
                            0 -> { onSidebarMode("year"); sidebarIdx = yearIndexIn(year, curYear) }
                            1 -> { onSidebarMode("quarter"); sidebarIdx = quarter }
                            2 -> { onSidebarMode("size"); sidebarIdx = SIZE_OPTIONS.indexOf(pageSize).coerceAtLeast(0) }
                            3 -> onFilters(filters.copy(hasEps = !hasEps, page = 1))   // 开关型,直接切换并停留原地
                            4 -> { onSidebarMode("sort"); sidebarIdx = if (sort == "latest") 1 else 0 }
                            5 -> onSettings()
                        }
                        else -> {
                            val t = dlTasks.getOrNull(sidebarIdx - mainItems.size) ?: return
                            taskMenuIdx = 0
                            taskMenu = t
                        }
                    }
                }
            } else {
                // 组合框:D-pad 上下首尾循环(返回上一级交给 BACK 键);确认选择后退回主列表(不关闭侧边栏,方便继续改其他项)
                val opts = pickerOptions
                when (key) {
                    K.UP -> if (sidebarIdx > 0) sidebarIdx-- else sidebarIdx = (opts.size - 1).coerceAtLeast(0)
                    K.DOWN -> if (sidebarIdx < opts.size - 1) sidebarIdx++ else sidebarIdx = 0
                    K.CENTER, K.ENTER -> {
                        val v = opts.getOrNull(sidebarIdx)?.value ?: 0
                        when (sidebarMode) {
                            "year" -> onFilters(filters.copy(year = v, page = 1))
                            "quarter" -> onFilters(filters.copy(quarter = v, page = 1))
                            "size" -> onFilters(filters.copy(pageSize = v, page = 1))
                            "sort" -> onFilters(filters.copy(sort = if (v == 1) "latest" else "season", page = 1))
                        }
                        onSidebarMode("main"); sidebarIdx = mainIndexOf(sidebarMode)
                    }
                }
            }
            return
        }
        when (block) {
            BLOCK_TAB -> when (key) {
                K.RIGHT -> if (col < 1) col++
                K.LEFT -> if (col > 0) col--
                K.DOWN -> { block = BLOCK_GRID; row = 0; col = minOf(col, COLS - 1) }
                K.CENTER, K.ENTER -> onTab(if (col == 0) HomeTab.LIST else HomeTab.FOLLOWED)
            }
            BLOCK_GRID -> when (key) {
                // 右移仅在下一格有卡片时允许(最后一行不满时不能走进空格子)
                K.RIGHT -> {
                    val next = row * COLS + col + 1
                    if (col < COLS - 1 && next < list.size) col++
                }
                K.LEFT -> if (col > 0) col--
                K.UP -> if (row > 0) row-- else { block = BLOCK_TAB; col = minOf(col, 1) }
                K.DOWN -> {
                    if (row < rows - 1) {
                        row++
                        // 下一行不满时,焦点收拢到该行实际卡片内
                        val inRow = minOf(COLS, list.size - row * COLS)
                        if (col >= inRow) col = inRow - 1
                    } else {
                        // 最后一行往下:焦点落在左半屏→上一页按钮,右半屏→下一页按钮
                        block = BLOCK_PAGER; col = if (col < COLS / 2) 0 else 1
                    }
                }
                K.CENTER, K.ENTER -> {
                    val idx = row * COLS + col
                    if (idx in list.indices) onAnimeClick(list[idx].anime)
                }
            }
            BLOCK_PAGER -> when (key) {
                K.RIGHT -> col = 1
                K.LEFT -> col = 0
                K.UP -> {
                    block = BLOCK_GRID
                    row = rows - 1
                    // 下一页(右半)按上→最后一行最后一个存在的格子;上一页(左半)按上→最后一行第一个格子
                    if (col == 1) {
                        val inRow = minOf(COLS, list.size - row * COLS)
                        col = (inRow - 1).coerceAtLeast(0)
                    } else {
                        col = 0
                    }
                }
                K.CENTER, K.ENTER -> {
                    if (col == 0) { if (page > 1) onFilters(filters.copy(page = page - 1)) }
                    else { if (page < pages) onFilters(filters.copy(page = page + 1)) }
                }
            }
        }
    }

    LaunchedEffect(keyHit.seq) {
        // 仅当首页在最上层(未进详情/设置)才处理按键,避免后台抢键;
        // 加载期间(全屏遮罩)锁定操作,避免作用于还没刷新的旧内容
        if (active && keyHit.seq > 0 && !loading) handleKey(keyHit.key)
    }

    // 焦点驱动滚动:Tab→顶,网格→对应行,分页→内容底
    LaunchedEffect(block, row, tabH.value, rowH.value, visH.value) {
        val vh = visH.value
        if (vh <= 0) return@LaunchedEffect
        when (block) {
            BLOCK_TAB -> if (scrollState.value > 0) scrollState.scrollTo(0)
            BLOCK_GRID -> {
                if (rowH.value > 0) {
                    val top = tabH.value + tabGap + row * (rowH.value + rowGap)
                    val cur = scrollState.value
                    val target = when {
                        top < cur -> top
                        top + rowH.value > cur + vh -> (top + rowH.value - vh).coerceAtLeast(0)
                        else -> cur
                    }
                    if (target != cur) scrollState.scrollTo(target.coerceAtMost(scrollState.maxValue))
                }
            }
            BLOCK_PAGER -> scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Anidock.Bg))   // 底色(背景图为半透明,垫深色底保证可读)
        BackgroundLayer()
        // 首页同样静默等背景图解码就绪再显示内容(与详情页一致),避免"内容先出、背景事后弹出"的闪烁
        val bgPath by BackgroundUi.path
        val bgLoading by BackgroundUi.loading
        if (bgPath.isEmpty() || !bgLoading) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .trackHeight { visH.value = it }
                .verticalScroll(scrollState)
                .padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth().trackHeight { tabH.value = it }) {
                TabBar(tab = tab, focusBlock = block, col = col)
            }
            Spacer(Modifier.height(20.dp))
            when {
                loading && list.isEmpty() -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Anidock.Accent,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("加载中…", color = Anidock.Text2, style = MaterialTheme.typography.titleMedium)
                    }
                }
                list.isEmpty() -> Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    val msg = when {
                        tab != HomeTab.FOLLOWED -> "这个筛选下没有番剧。"
                        year == 0 && quarter == 0 && !hasEps -> "还没有追番。番剧列表里按菜单可关注。"
                        else -> "这个筛选下没有追番。"
                    }
                    Text(msg, color = Anidock.Text3, style = MaterialTheme.typography.titleMedium)
                }
                else -> GridWithPager(
                    list = list, block = block, col = col, row = row,
                    page = page, pages = pages, total = total, pageSize = pageSize,
                    rowH = rowH,
                )
            }
        }
        }
        // 切 tab / 翻页 / 改筛选:数据加载期间盖全屏半透明遮罩 + 加载动画,遮住旧列表,
        // 避免"新 tab/新页已选中、内容还是上一次的"错位观感;放在侧边栏下方,侧边栏打开时仍可用
        if (loading && list.isNotEmpty()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Anidock.Accent,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        if (showSidebar) Sidebar(
            mode = sidebarMode,
            mainItems = mainItems,
            pickerTitle = pickerTitle,
            pickerOptions = pickerOptions,
            focusIdx = sidebarIdx,
            dlTasks = dlTasks,
            diskInfo = diskInfo,
        )
        taskMenu?.let { m -> TaskMenuOverlay(m, taskMenuIdx) }
    }
}

/** 任务菜单动作(与详情页剧集菜单同源同语义)。 */
private fun taskActions(t: DownloadTaskView): List<String> = when (t.status) {
    "paused" -> listOf("继续下载", "取消下载", "删除任务和文件")
    "queued", "downloading", "seeding" -> listOf("暂停下载", "取消下载", "删除任务和文件")
    "error" -> listOf("重试", "删除任务和文件")
    else -> listOf("删除任务和文件")
}

private fun epText(ep: Float): String =
    if (ep == ep.toLong().toFloat()) ep.toLong().toString() else ep.toString()

/** 任务操作菜单覆盖层:与详情页剧集菜单同风格,↑↓ 选择,确认执行,BACK 关闭。 */
@Composable
private fun TaskMenuOverlay(menu: DownloadTaskView, idx: Int) {
    val actions = taskActions(menu)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .width(380.dp)
                .border(2.dp, Anidock.Accent, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "${menu.title} 第${epText(menu.episode)}集",
                    color = Anidock.Text3,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                actions.forEachIndexed { i, act ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(if (i == idx) Anidock.Accent.copy(alpha = 0.25f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(act, color = if (i == idx) Color.White else Anidock.Text2, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** 顶部两个选择卡:番剧列表 / 我的追番。 */
@Composable
private fun TabBar(tab: HomeTab, focusBlock: Int, col: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TabCard("番剧列表", selected = tab == HomeTab.LIST, focused = focusBlock == BLOCK_TAB && col == 0)
        TabCard("我的追番", selected = tab == HomeTab.FOLLOWED, focused = focusBlock == BLOCK_TAB && col == 1)
    }
}

/** 下划线样式:无按钮形态,选中 = accent 文字 + 实色下划线;聚焦(未选中) = 提亮文字 + 半透明下划线;
 *  未选中未聚焦 = 纯灰字、下划线占位透明(保持高度一致,出现时不跳动)。 */
@Composable
private fun TabCard(label: String, selected: Boolean, focused: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 获焦(无论是否已选中)整块放大 12% 作为焦点反馈,解决"选中 tab 再获焦看不出变化"
        modifier = Modifier
            .width(160.dp)
            .padding(vertical = 8.dp)
            .graphicsLayer {
                val s = if (focused) 1.12f else 1.0f
                scaleX = s
                scaleY = s
            },
    ) {
        Text(
            label,
            color = when {
                focused -> Anidock.Text      // 获焦:提亮为白(选中/未选中一致,配合放大)
                selected -> Anidock.Accent    // 选中未获焦:accent
                else -> Anidock.Text2
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(3.dp)
                .background(
                    when {
                        selected -> Anidock.Accent
                        focused -> Anidock.Accent.copy(alpha = 0.45f)
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(3.dp),
                ),
        )
    }
}

/** 卡片网格 + 分页条(纯渲染,滚动由 HomeScreen 统一驱动)。分页条在内容末尾。 */
@Composable
private fun GridWithPager(
    list: List<AnimeCardData>,
    block: Int, col: Int, row: Int,
    page: Int, pages: Int, total: Int, pageSize: Int,
    rowH: MutableState<Int>,
) {
    Column {
        list.chunked(COLS).forEachIndexed { r, rowList ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .trackHeight { rowH.value = it }
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(COLS) { c ->
                    val item = rowList.getOrNull(c)
                    Box(modifier = Modifier.weight(1f)) {
                        if (item != null) {
                            AnimeCard(
                                data = item,
                                isFocused = block == BLOCK_GRID && r * COLS + c == row * COLS + col,
                            )
                        }
                    }
                }
            }
        }
        // 分页条始终显示:单页时前后按钮均禁用(变暗),但保留底部留白,避免卡片贴底
        PaginationBar(
            page = page, pages = pages, total = total, pageSize = pageSize,
            focusBlock = block, col = col,
        )
    }
}

/** 底部分页条:居中的分段胶囊 ‹ 上一页 / 页码信息 / 下一页 ›。整段 clip 成圆角,获焦段底色高亮。 */
@Composable
private fun PaginationBar(
    page: Int, pages: Int, total: Int, pageSize: Int,
    focusBlock: Int, col: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x99141926), RoundedCornerShape(12.dp))
                .border(1.dp, Anidock.Border, RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PagerSegment("‹ 上一页", enabled = page > 1, focused = focusBlock == BLOCK_PAGER && col == 0)
            Box(Modifier.height(48.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "第 $page / $pages 页 · 共 $total 部 · 每页 $pageSize",
                    color = Anidock.Text3,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PagerSegment("下一页 ›", enabled = page < pages, focused = focusBlock == BLOCK_PAGER && col == 1)
        }
    }
}

@Composable
private fun PagerSegment(label: String, enabled: Boolean, focused: Boolean) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .background(if (focused) Anidock.Accent.copy(alpha = 0.20f) else Color.Transparent)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> Anidock.Accent
                !enabled -> Color(0xFF4A5368)   // 禁用(已到边界):明显更暗
                else -> Anidock.Text2
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private val SIZE_OPTIONS = listOf(8, 12, 16, 24)

/** 排序值 → 显示名(侧边栏"排序"行的当前值)。 */
private fun sortLabel(s: String): String = when (s) {
    "latest" -> "最新更新"
    else -> "年度 / 季度"
}

private fun yearLabel(y: Int): String = if (y == 0) "全部年份" else "$y"

private fun quarterLabel(q: Int): String = when (q) {
    0 -> "全部季度"
    1 -> "1 月(冬)"
    2 -> "4 月(春)"
    3 -> "7 月(夏)"
    4 -> "10 月(秋)"
    else -> ""
}

/** 年份在 picker 选项 [0, curYear, curYear-1, ...] 中的下标。 */
private fun yearIndexIn(year: Int, curYear: Int): Int = if (year == 0) 0 else (curYear - year) + 1

private fun mainIndexOf(mode: String): Int = when (mode) {
    "year" -> 0
    "quarter" -> 1
    "size" -> 2
    "sort" -> 4
    else -> 0
}
