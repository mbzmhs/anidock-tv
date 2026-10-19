package com.mbzmhs.anidocktv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 背景图运行时状态:设置页修改后首页/详情/设置页即时生效。
 * 用 SnapshotState(可在 composition 外读写),避免重建全局服务单例。
 */
object BackgroundUi {
    val path = mutableStateOf("")      // 空 = 未启用背景图
    val opacity = mutableStateOf(0.4f) // 0-1
    val bitmap = mutableStateOf<ImageBitmap?>(null) // 解码好的位图,全局共享;各页只读缓存不再各自解码(消除"背景事后弹出"闪烁)
    val loading = mutableStateOf(true)              // 初始视为"未解码完"(保守等待,门控据此静默挡页面);BackgroundController 解码完或无背景时置 false
}

/** 解码后长边上限:TV 1080p 屏,留 2 倍余量保证清晰度,防止大图撑爆内存。 */
private const val MAX_DIM = 2560

/** 支持的图片扩展名(小写)。 */
val BG_IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp")

/** 解码背景图(须在 IO 线程调用):文件不存在/格式不支持时返回 null。 */
fun loadBackground(path: String): Bitmap? {
    val f = File(path)
    if (!f.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(f.absolutePath, bounds) }
        .onFailure { return null }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIM) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(f.absolutePath, opts) }.getOrNull()
}

/**
 * 背景图解码器:在根层 compose 一次,把解码结果缓存到 [BackgroundUi.bitmap]。
 * 首页/详情页的 [BackgroundLayer] 只读缓存即时绘制,不再各自重复解码,
 * 从而消除"进详情页背景图事后弹出"的闪烁;换图(path 变化)时失效旧位图重新解码。
 */
@Composable
fun BackgroundController() {
    val p by BackgroundUi.path
    LaunchedEffect(p) {
        if (p.isEmpty()) {
            BackgroundUi.bitmap.value = null
            BackgroundUi.loading.value = false
            return@LaunchedEffect
        }
        BackgroundUi.bitmap.value = null
        BackgroundUi.loading.value = true
        val b = withContext(Dispatchers.IO) { loadBackground(p)?.asImageBitmap() }
        BackgroundUi.bitmap.value = b
        BackgroundUi.loading.value = false
    }
}

/**
 * 背景图层:各页面根 Box 的底层(底色之上、内容之下),只读 [BackgroundUi.bitmap] 缓存即时绘制。
 * 无背景图或图片加载失败时不绘制任何东西(透出底色)。
 */
@Composable
fun BackgroundLayer() {
    val op by BackgroundUi.opacity
    val bmp by BackgroundUi.bitmap
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(op),
            contentScale = ContentScale.Crop,
        )
    }
}
