package com.mbzmhs.anidocktv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 自适应尺寸基线。
 *
 * 电视:观看距离固定,排版"比例正确"比"塞更多内容"重要。以 960x540dp 为设计画布,
 * 窗口 dp 画布更大时按 min(宽/960, 高/540) 等比放大(上限 2x,避免 8K/异常密度无限放大):
 * - 4K 逻辑 UI(3840x2160px @320dpi = 1920x1080dp)会折算回 960x540dp,
 *   与 1080p 电视的排版比例一致,不会出现字小一半、面板缩水的观感;
 * - 21:9 等更宽的电视只按高度允许的比例放大,多出来的宽度用于增加网格列数。
 *
 * 触摸设备(手机/平板):系统密度通常已按物理尺寸配置好,不做整体缩放;
 * 首页列数随有效宽度增加,平板横屏能自然多放几列。
 *
 * 所有 dp→px 的动态计算(焦点滚动、锚点、行高)继续走同一个 LocalDensity,
 * 因此缩放后各页面现有的 px 计算口径仍然自洽。
 */
@Immutable
data class UiMetrics(
    val isTelevision: Boolean,
    /** 设计画布缩放倍率;1 = 不缩放。 */
    val scale: Float,
    /** 折算回设计画布后的可用宽/高(dp),用于网格列数等结构决策。 */
    val effectiveWidthDp: Float,
    val effectiveHeightDp: Float,
    /** 首页网格列数:960dp 画布 = 4;更宽的窗口/平板 = 5..8。 */
    val homeColumns: Int,
) {
    companion object {
        /** 首页左右 padding 合计(40dp * 2)。 */
        const val HOME_PADDING_H = 80f
        /** 单张封面卡目标宽度(dp,1080p 电视下约 208dp)。 */
        const val HOME_CARD_TARGET = 208f
        /** 卡片间距(dp)。 */
        const val HOME_GAP = 16f

        fun from(context: Context, configuration: Configuration): UiMetrics {
            val widthDp = configuration.screenWidthDp.toFloat()
            val heightDp = configuration.screenHeightDp.toFloat()
            val pm = context.packageManager
            val uiMode = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
            // 电视盒子/电视系统;无触摸屏的 Android 设备也按电视处理
            val isTelevision = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

            val scale = if (isTelevision) {
                minOf(widthDp / 960f, heightDp / 540f).coerceIn(1f, 2f)
            } else 1f
            val effectiveWidth = widthDp / scale
            val effectiveHeight = heightDp / scale
            val columns = ((effectiveWidth - HOME_PADDING_H + HOME_GAP) /
                (HOME_CARD_TARGET + HOME_GAP)).toInt().coerceIn(2, 8)
            return UiMetrics(
                isTelevision = isTelevision,
                scale = scale,
                effectiveWidthDp = effectiveWidth,
                effectiveHeightDp = effectiveHeight,
                homeColumns = columns,
            )
        }
    }
}

val LocalUiMetrics = staticCompositionLocalOf { UiMetrics(false, 1f, 960f, 540f, 4) }

/**
 * 根节点提供自适应尺寸,并把 LocalDensity 换成"设计画布密度"。
 * 只影响 Compose 的 dp/sp 换算;SurfaceView 视频层和 VLC 的窗口尺寸仍按真实像素走。
 */
@Composable
fun ProvideAnidockUi(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    val metrics = remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.uiMode,
    ) { UiMetrics.from(context, configuration) }
    // 缩放的是 dp/sp→px 的换算比例,窗口像素和滚动状态不受影响
    val contentDensity = remember(baseDensity, metrics.scale) {
        if (metrics.scale == 1f) baseDensity
        else Density(baseDensity.density * metrics.scale, baseDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalUiMetrics provides metrics,
        LocalDensity provides contentDensity,
        content = content,
    )
}