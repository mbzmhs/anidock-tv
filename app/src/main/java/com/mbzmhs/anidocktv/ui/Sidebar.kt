package com.mbzmhs.anidocktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.data.DownloadTaskView
import com.mbzmhs.anidocktv.ui.theme.Anidock

/** 下载根目录所在卷的剩余空间与占比(如"磁盘余量 412.6 GB · 97%");取不到返回空串。statvfs 轻量调用,3s 轮询刷新。 */
fun diskFreeLabel(): String = try {
    val stat = android.os.StatFs(Services.downloadDir)
    val free = stat.availableBlocksLong * stat.blockSizeLong
    val total = stat.blockCountLong * stat.blockSizeLong
    if (total <= 0) "" else "磁盘余量 ${fmtSize(free)} · ${free * 100 / total}%"
} catch (_: Exception) {
    ""
}

/** 侧边栏一行:名称 + 可选当前值(主列表) 或 选项(组合框) + 选中态。
 *  clear=true 表示"点按清除"(如搜索词行),行尾显示 ✕ 清除 提示。 */
data class SideOption(
    val label: String,
    val value: Int = 0,
    val selected: Boolean = false,
    val sub: String = "",
    val clear: Boolean = false,
)

/**
 * 侧边栏(焦点即状态,无状态渲染):
 * - main 模式:6 行(年份/季度/每页数量/只看有剧集/排序/设置)+ 「下载任务」区(进行中+出错任务);
 *   任务行可聚焦,CENTER 由 HomeScreen 弹操作菜单(暂停/继续/取消/重试/删除,与详情页同语义);
 * - picker 模式(组合框):某一项的选项列表,选中项打勾。
 * 焦点与按键由 HomeScreen 统一管理,这里只按 focusIdx 高亮并滚动。
 */
@Composable
fun Sidebar(
    mode: String,
    mainItems: List<SideOption>,
    pickerTitle: String,
    pickerOptions: List<SideOption>,
    focusIdx: Int,
    dlTasks: List<DownloadTaskView>,
    diskInfo: String = "",
) {
    val listState = rememberLazyListState()
    val isMain = mode == "main"
    val taskCount = if (isMain) dlTasks.size else 0
    val total = if (isMain) mainItems.size + taskCount else pickerOptions.size
    LaunchedEffect(focusIdx, mode, taskCount) {
        // 「下载任务」区标题是非聚焦行,却占一个 LazyColumn item 排在任务行前;
        // 任务行(focus idx = mainItems.size+j)对应的 LazyColumn item 是 (focusIdx+1)。
        if (focusIdx in 0 until total) {
            val target = if (isMain && focusIdx >= mainItems.size) focusIdx + 1 else focusIdx
            listState.animateScrollToItem(target)
        }
    }
    Surface(
        color = Anidock.BgSoft,
        modifier = Modifier
            .width(360.dp)
            .fillMaxHeight()
            .border(width = 1.dp, color = Anidock.BorderSoft),
    ) {
        Column(modifier = Modifier.padding(vertical = 20.dp)) {
            Text(
                if (isMain) "筛选 / 设置 / 下载" else pickerTitle,
                color = Anidock.Text2,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (!isMain) {
                Text(
                    "↑↓ 选择 · 确认 应用 · 再按 ↑ 返回",
                    color = Anidock.Text3,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
            }
            if (isMain) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(mainItems.size) { idx ->
                        SideRow(mainItems[idx], focused = idx == focusIdx, isMain = true)
                    }
                    // 「下载任务」区标题永远显示(哪怕 0 任务),让用户随时能看到磁盘余量
                    item { DlSectionHeader(dlTasks.size, diskInfo) }
                    items(dlTasks.size) { j ->
                        DlTaskRow(dlTasks[j], focused = mainItems.size + j == focusIdx)
                    }
                    if (dlTasks.isEmpty()) {
                        item {
                            Text(
                                "暂无进行中下载",
                                color = Anidock.Text3,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            } else {
                LazyColumn(state = listState) {
                    items(total) { idx ->
                        SideRow(pickerOptions[idx], focused = idx == focusIdx, isMain = false)
                    }
                }
            }
        }
    }
}

/** 「下载任务」分区标题行;括号内为下载目录所在卷的剩余空间与占比。 */
@Composable
private fun DlSectionHeader(count: Int, diskInfo: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("下载任务", color = Anidock.Text2, style = MaterialTheme.typography.labelLarge)
        if (diskInfo.isNotBlank()) {
            Text(" ($diskInfo)", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.weight(1f))
        Text("${count} 个任务", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
    }
}

/** 单个任务行:状态点 + 「番剧名 · 第x集」+ 进度/速度;聚焦可弹操作菜单。 */
@Composable
private fun DlTaskRow(t: DownloadTaskView, focused: Boolean) {
    val dot = when (t.status) {
        "downloading" -> Anidock.Accent
        "paused" -> Anidock.Accent2
        "seeding" -> Anidock.Ok
        "error" -> Anidock.Err
        "choosing" -> Anidock.Accent
        else -> Anidock.Text3
    }
    val sz = fmtSize(t.size)
    val dl = fmtSize(t.downloaded)
    val sub = when (t.status) {
        "downloading" -> {
            val sizePart = if (sz.isNotEmpty()) "${dl}/$sz" else dl
            "${(t.progress * 100f).toInt()}% · ${fmtSpeed(t.speed)}" + if (sizePart.isNotEmpty()) " · $sizePart" else ""
        }
        "choosing" -> "待选文件" + if (sz.isNotEmpty()) " · $sz" else ""
        "queued" -> "排队中" + if (sz.isNotEmpty()) " · $sz" else ""
        "paused" -> "已暂停" + if (sz.isNotEmpty()) " · $sz" else ""
        "seeding" -> "做种中" + if (sz.isNotEmpty()) " · $sz" else ""
        "error" -> {
            // 出错行显示已下体量(分片还在,可续传);体量未知时只显示"出错"
            val sizePart = if (dl.isNotEmpty()) "已下 $dl" else ""
            "出错" + if (sizePart.isNotEmpty()) " · $sizePart" else ""
        }
        else -> sz
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(if (focused) Anidock.CardHover else Color.Transparent, RoundedCornerShape(10.dp)),
    ) {
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .width(4.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Anidock.Accent),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (focused) 16.dp else 12.dp, top = 5.dp, end = 12.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${t.title} ${epLabel(t.episode)}",
                    color = Anidock.Text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        color = when {
                            t.status == "error" -> Anidock.Err
                            t.status == "paused" -> Anidock.Accent2
                            focused -> Anidock.Text2
                            else -> Anidock.Text3
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 集标签:负键(含 -1)=合集(区间解析归详情页按钮,侧边栏只标"合集")。 */
private fun epLabel(ep: Float): String =
    if (ep < 0f) "· 合集"
    else if (ep == ep.toLong().toFloat()) "· 第${ep.toLong()}集" else "· 第${ep}集"

private fun fmtSpeed(bps: Long): String = when {
    bps >= 1048576 -> "%.1f MB/s".format(bps / 1048576.0)
    bps >= 1024 -> "${bps / 1024} KB/s"
    bps > 0 -> "${bps} B/s"
    else -> "等待 peer…"
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1073741824 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1048576 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    bytes > 0 -> "${bytes} B"
    else -> ""
}

/** 焦点样式(B 左竖条):聚焦行微填充 + 左侧一根 accent 竖条 + 文字提亮加粗,一眼定位。 */
@Composable
private fun SideRow(it: SideOption, focused: Boolean, isMain: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .background(if (focused) Anidock.CardHover else Color.Transparent, RoundedCornerShape(10.dp)),
    ) {
        if (focused) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Anidock.Accent),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (focused) 16.dp else 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                it.label,
                color = when { focused -> Anidock.Text; it.selected -> Anidock.Accent; else -> Anidock.Text2 },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (focused || it.selected) FontWeight.Bold else FontWeight.Normal),
                modifier = Modifier.weight(1f),
            )
            if (isMain) {
                if (it.sub.isNotBlank()) Text(it.sub, color = Anidock.Text3, style = MaterialTheme.typography.bodyMedium)
                if (it.clear) {
                    // 可清除行(搜索词):行尾"✕ 清除"提示,替代默认"›"
                    Text("  ✕ 清除", color = Anidock.Text3, style = MaterialTheme.typography.bodyMedium)
                } else if (it.label != "设置") {
                    Text("›", color = Anidock.Text3, style = MaterialTheme.typography.titleMedium)
                }
            } else if (it.selected) {
                Text("✓", color = Anidock.Accent, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
