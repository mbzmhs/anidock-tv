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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbzmhs.anidocktv.data.EngineService
import com.mbzmhs.anidocktv.data.EngineService.ZdlFile
import com.mbzmhs.anidocktv.ui.theme.Anidock
import com.mbzmhs.anidocktv.ui.tv.K
import com.mbzmhs.anidocktv.ui.tv.KeyHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 可边下边播的视频扩展名(与 DetailScreen 播放定位同一口径)。 */
private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "avi", "mov", "ts", "flv", "wmv", "webm", "mpg", "mpeg")

/** 列表行:目录头行(整组勾选,子目录按路径逐级出现)或文件行。 */
private sealed interface FR {
    data class Dir(val prefix: List<String>, val kids: List<Int>) : FR
    data class F(val idx: Int) : FR
}

private fun ZdlFile.relPath(): String = path.joinToString("/")

/**
 * 未落定的勾选暂存(按任务 id,进程内存活):对话框内勾选后按 BACK 退出并没提交引擎
 * (choosing 态 WantedFiles 仍为空=全选),重开时引擎回显不了这些勾选——暂存让
 * BACK 不丢勾选、重开恢复;「开始下载」落定后引擎口径即权威,清掉暂存。
 */
private val pendingSelection = mutableMapOf<String, Set<String>>()

private fun isVideoFile(f: ZdlFile): Boolean =
    f.path.lastOrNull()?.substringAfterLast('.', "")?.lowercase() in VIDEO_EXTS

/** 路径逐段字典序(段数少的排前,保证目录头行先于其下内容出现)。 */
private fun cmpPaths(a: List<String>, b: List<String>): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val c = a[i].compareTo(b[i])
        if (c != 0) return c
    }
    return a.size - b.size
}

/** 按目录结构展平成行序列:目录头行先于其下内容(含中间目录),文件按路径排序。 */
private fun buildRows(entries: List<ZdlFile>): List<FR> {
    if (entries.isEmpty()) return emptyList()
    val order = entries.indices.sortedWith { a, b -> cmpPaths(entries[a].path, entries[b].path) }
    val out = mutableListOf<FR>()
    val seen = mutableSetOf<List<String>>()
    for (i in order) {
        val p = entries[i].path
        for (d in 1 until p.size) {
            val prefix = p.subList(0, d)
            if (seen.add(prefix)) out.add(FR.Dir(prefix, emptyList()))
        }
        out.add(FR.F(i))
    }
    return out.map { r ->
        if (r is FR.Dir) r.copy(
            kids = entries.indices.filter { entries[it].path.size > r.prefix.size && entries[it].path.subList(0, r.prefix.size) == r.prefix }
        ) else r
    }
}

/**
 * 合集(多文件种子)文件列表对话框:
 * - 引擎任务文件树(子目录为目录头行,确认=整组勾选/取消;文件行确认=单个勾选);
 * - 底部动作行:只选视频 / 全选 / 开始下载(把勾选落定到引擎,choosing 转排队、进行中按新选择重启);
 * - 视频文件行按 RIGHT=就地边下边播该文件(任务需在下载/做种/完成;choosing 需先开始下载);
 * - 3s 轮询文件进度;勾选只在对话框内改动,轮询不覆盖。
 * 独立组件:只依赖 taskId/keyHit,不 import 业务类型;宿主屏幕需在对话框打开时停用自己的按键处理。
 */
@Composable
fun TorrentFilesOverlay(
    taskId: String,
    title: String,
    keyHit: KeyHit,
    active: Boolean,
    onClose: () -> Unit,
    onPlayFile: (relPath: String, fileName: String) -> Unit,
) {
    BackHandler { onClose() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadErr by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ZdlFile>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }   // null=首次加载成功前
    var idx by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    // 打开对话框的那次按键(菜单/剧集上的确认)不能泄漏进对话框:首次组合时
    // LaunchedEffect 会以当前 seq 立即触发一次,若不拦,文件列表还没加载完
    // (rows 为空)就会落进底部动作行,把勾选锁成空集合。
    val openedSeq = remember { keyHit.seq }

    // 首次加载 + 3s 轮询(进度/状态);引擎失联时保留已加载内容继续重试。
    // 初始勾选:优先恢复上次未落定的暂存(BACK 退出不丢),否则按引擎 wanted 回显;
    // 仅在拿到非空文件表后才锁定(空响应不锁,防止把勾选定死为空)。
    LaunchedEffect(taskId) {
        while (true) {
            val d = EngineService.taskDetail(taskId)
            if (d != null) {
                entries = d.files
                status = d.status
                if (selected == null && d.files.isNotEmpty()) {
                    selected = pendingSelection[taskId]?.takeIf { it.isNotEmpty() }
                        ?: d.files.filter { it.wanted }.map { it.relPath() }.toSet()
                }
                loading = false
                loadErr = ""
            } else if (selected == null) {
                loadErr = "加载失败,正在重试…"
            }
            delay(3000)
        }
    }

    val rows = remember(entries) { buildRows(entries) }
    val actionCount = 3   // 只选视频 / 全选 / 开始下载
    val total = rows.size + actionCount
    if (total > 0 && idx >= total) idx = total - 1
    LaunchedEffect(idx, total) {
        if (idx in 0 until total) runCatching { listState.animateScrollToItem(idx) }
    }

    val sel = selected ?: emptySet()
    val playable = status in setOf("running", "seeding", "completed")

    fun setSel(s: Set<String>) {
        selected = s
        // 空勾选无暂存意义(重开按引擎口径回显),非空才暂存:BACK 退出不丢,重开恢复
        if (s.isEmpty()) pendingSelection.remove(taskId) else pendingSelection[taskId] = s
    }

    fun toggleFile(i: Int) {
        val p = entries.getOrNull(i)?.relPath() ?: return
        setSel(if (p in sel) sel - p else sel + p)
    }

    fun toggleDir(kids: List<Int>) {
        val paths = kids.mapNotNull { entries.getOrNull(it)?.relPath() }
        if (paths.isEmpty()) return
        setSel(if (paths.all { it in sel }) sel - paths.toSet() else sel + paths.toSet())
    }

    fun applyDownload() {
        if (sel.isEmpty()) {
            Toast.makeText(context, "至少选择一个文件", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            if (EngineService.chooseFiles(taskId, sel.toList())) {
                pendingSelection.remove(taskId)   // 已落定,此后以引擎 wanted 回显为准
                onClose()
            }
            else Toast.makeText(context, "保存失败,稍后再试", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(keyHit.seq) {
        // 打开对话框的那次按键(seq<=openedSeq)不消费;列表未加载完(rows 空)时确认键不吃进动作行
        if (!active || keyHit.seq <= openedSeq) return@LaunchedEffect
        when (keyHit.key) {
            K.UP -> if (idx > 0) idx--
            K.DOWN -> if (idx < total - 1) idx++
            K.RIGHT -> {
                // 视频文件行:RIGHT=就地边下边播(不用长按,避免与播放器的"长按=菜单"打架)
                val r = rows.getOrNull(idx)
                if (r is FR.F) {
                    val f = entries.getOrNull(r.idx)
                    if (f != null && isVideoFile(f)) {
                        if (playable) onPlayFile(f.relPath(), f.path.last())
                        else Toast.makeText(context, "任务未在下载,先选文件开始下载", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            K.CENTER, K.ENTER -> if (rows.isNotEmpty()) {
                val r = rows.getOrNull(idx)
                when {
                    r is FR.Dir -> toggleDir(r.kids)
                    r is FR.F -> toggleFile(r.idx)
                    else -> when (idx - rows.size) {   // 底部动作行
                        0 -> setSel(entries.filter { isVideoFile(it) }.map { it.relPath() }.toSet())
                        1 -> setSel(entries.map { it.relPath() }.toSet())
                        else -> applyDownload()
                    }
                }
            }
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .width(760.dp)
                .height(640.dp)
                .border(2.dp, Anidock.Accent, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "合集文件",
                        color = Anidock.Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (loading) CircularProgressIndicator(color = Anidock.Accent, strokeWidth = 2.dp, modifier = Modifier.height(16.dp).width(16.dp))
                    Text(
                        title,
                        color = Anidock.Text3,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                val selBytes = entries.filter { it.relPath() in sel }.sumOf { it.size }
                val statusText = when (status) {
                    "choosing" -> "待选文件"
                    "running" -> "下载中"
                    "seeding" -> "做种中"
                    "completed" -> "已完成"
                    "paused" -> "已暂停"
                    "waiting" -> "排队中"
                    else -> status
                }
                Text(
                    when {
                        entries.isEmpty() && loadErr.isNotEmpty() -> loadErr
                        entries.isEmpty() && !loading -> "种子内没有文件"
                        else -> "$statusText · 共 ${entries.size} 个文件 · 已选 ${sel.size} 个(${fmtSize(selBytes)})"
                    },
                    color = Anidock.Text3,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(rows.size) { i ->
                        when (val r = rows[i]) {
                            is FR.Dir -> DirRow(r, entries, sel, focused = i == idx)
                            is FR.F -> FileRow(entries[r.idx], selected = entries[r.idx].relPath() in sel, focused = i == idx)
                        }
                    }
                    item { ActionRow("只选视频文件", idx == rows.size + 0, strong = false) }
                    item { ActionRow("全选", idx == rows.size + 1, strong = false) }
                    item { ActionRow("开始下载(已选 ${sel.size} 个)", idx == rows.size + 2, strong = true) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "确认 勾选/整组切换 · 右键 播放视频 · BACK 返回",
                    color = Anidock.Text3,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 文件行:勾选框 + 文件名(含 ▶ 提示可播)+ 大小(已下载时附进度)。 */
@Composable
private fun FileRow(f: ZdlFile, selected: Boolean, focused: Boolean) {
    val depth = (f.path.size - 1).coerceAtLeast(0)
    val video = isVideoFile(f)
    val pct = if (f.size > 0 && f.done in 1 until f.size) " · ${f.done * 100 / f.size}%" else ""
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (10 + depth * 18).dp)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Anidock.Accent.copy(alpha = 0.22f) else Color.Transparent)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (selected) "☑" else "☐",
            color = if (selected) Anidock.Accent else Anidock.Text3,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            f.path.last(),
            color = if (focused) Color.White else Anidock.Text2,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (video) Text("▶", color = Anidock.Accent, style = MaterialTheme.typography.labelMedium)
        Text(
            fmtSize(f.size) + pct,
            color = Anidock.Text3,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 目录头行:子目录名 + 已选/总数与体量;确认=整组(含更深层)勾选/取消。 */
@Composable
private fun DirRow(r: FR.Dir, entries: List<ZdlFile>, sel: Set<String>, focused: Boolean) {
    var n = 0
    var selN = 0
    var bytes = 0L
    for (i in r.kids) {
        val f = entries.getOrNull(i) ?: continue
        n++
        bytes += f.size
        if (f.relPath() in sel) selN++
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (2 + (r.prefix.size - 1) * 18).dp)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Anidock.Accent.copy(alpha = 0.22f) else Anidock.BgSoft)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (n > 0 && selN == n) "▾☑" else "▾☐",
            color = Anidock.Accent,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            r.prefix.lastOrNull() ?: "",
            color = if (focused) Color.White else Anidock.Text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$selN/$n · ${fmtSize(bytes)}",
            color = Anidock.Text3,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 底部动作行:strong=主操作(开始下载),聚焦时实心高亮。 */
@Composable
private fun ActionRow(text: String, focused: Boolean, strong: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused && strong -> Anidock.Accent
                    focused -> Anidock.Accent.copy(alpha = 0.22f)
                    strong -> Anidock.Accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                if (strong) Anidock.Accent.copy(alpha = 0.6f) else Anidock.BorderSoft,
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (focused && strong) Color.White else if (strong) Anidock.Text else Anidock.Text2,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal),
        )
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1073741824 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1048576 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    bytes > 0 -> "$bytes B"
    else -> "0 B"
}
