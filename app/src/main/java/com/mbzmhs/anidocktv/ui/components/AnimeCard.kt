package com.mbzmhs.anidocktv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.data.AnimeCardData
import com.mbzmhs.anidocktv.ui.theme.Anidock
import coil.compose.AsyncImage

/**
 * 方形封面卡片(对齐 web AnimeCard)。选中态:粗边框 + 放大 + 底色提亮。
 * 封面底部叠季度 chip(左) 与 胶囊堆叠(右):已下载(绿)/上次看到(琥珀)在上,更新至(蓝紫渐变)在下。
 * 焦点由外部状态(isFocused)决定,不参与系统焦点。
 */
@Composable
fun AnimeCard(
    data: AnimeCardData,
    isFocused: Boolean,
) {
    val anime = data.anime
    // 外层 Box 承载"骑在卡外"的未读蓝点,缩放也一并套在外面,蓝点跟着卡片走
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.08f else 1f),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .fillMaxWidth()
                // 聚焦辉光:accent 彩色阴影从卡片四周透出,替代原来"无光"的粗边框
                .shadow(
                    elevation = if (isFocused) 18.dp else 0.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = Anidock.Accent.copy(alpha = if (isFocused) 0.55f else 0f),
                    spotColor = Anidock.Accent.copy(alpha = if (isFocused) 0.65f else 0f),
                )
                .border(
                    width = if (isFocused) 3.dp else 1.dp,
                    color = if (isFocused) Anidock.Accent else Anidock.BorderSoft,
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
            ) {
                if (anime.cover.isNotBlank()) {
                    AsyncImage(
                        model = Services.api.resolveMedia(anime.cover),
                        contentDescription = anime.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(anime.title.take(1), color = Color(0xFF2A3350), style = MaterialTheme.typography.displayLarge)
                    }
                }
                // 底部渐变遮罩,叠 chip
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xDD0A0D13)),
                            startY = 0.5f,
                        )),
                )
                // 左上角:下载进度圆环 + 剩余n集(在途集数>0 才显示,全部下完即消失);深色胶囊垫底保证亮封面可读
                if (data.activeDlCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xD20A0D13))
                            .padding(start = 5.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        DlProgressRing(data.activeDlProgress)
                        Text(
                            buildAnnotatedString {
                                pushStyle(SpanStyle(color = Anidock.Text)); append("剩余"); pop()
                                pushStyle(SpanStyle(color = Anidock.Accent, fontWeight = FontWeight.Bold)); append(" ${data.activeDlCount} "); pop()
                                pushStyle(SpanStyle(color = Anidock.Text)); append("集"); pop()
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (anime.seasonLabel.isNotBlank()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, Anidock.Accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(anime.seasonLabel, color = Color(0xFFA9C2FF), style = MaterialTheme.typography.labelSmall)
                    }
                }
                // 右底:胶囊堆叠(自上而下)已下载→上次看到→更新至,均置于"更新至"上方
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    if (data.downloadedCount > 0) {
                        InfoCapsule("已下载 ${data.downloadedCount} 集", Color(0xFF2E9E5B))
                    }
                    if (anime.lastOpenedEpisode > 0f) {
                        InfoCapsule("上次看到 ${epText(anime.lastOpenedEpisode)}", Color(0xFFE8862B))
                    }
                    if (anime.latestEpisode > 0f) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(brush = Brush.linearGradient(listOf(Anidock.Accent, Anidock.Accent2)))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("更新至 ${epText(anime.latestEpisode)}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // 标题:单行超长省略(聚焦不放开成 2 行,避免卡片增高顶走下面那行)
            Text(
                text = anime.title,
                color = Anidock.Text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
        }
        // 未读蓝点:18dp,骑在卡片右上角外侧(中心落在卡角,一半在内一半在外),白描边保证亮色封面也醒目
        if (data.hasNewEpisodes) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 7.dp, y = (-7).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Anidock.Accent)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            )
        }
    }
}

/** 实色胶囊(白字加粗):封面"已下载/上次看到"用,实色底比"更新至"的渐变更显眼。 */
@Composable
private fun InfoCapsule(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** 首页卡片下载进度圆环:半透明白轨道 + accent 进度弧,从顶部顺时针填充(0-1)。 */
@Composable
private fun DlProgressRing(progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(32.dp)) {
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color = Color(0x38FFFFFF), style = stroke)      // 轨道
        if (p > 0.02f) {
            drawArc(
                color = Anidock.Accent,
                startAngle = -90f,
                sweepAngle = p * 360f,
                useCenter = false,
                style = stroke,
            )
        }
    }
}

private fun epText(x: Float): String = if (x % 1f == 0f) "${x.toInt()}" else "${Math.round(x * 10) / 10f}"
