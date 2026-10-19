package com.mbzmhs.anidocktv.ui.tv

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key

/**
 * 一次按键事件(带自增序号,让 LaunchedEffect 能区分"新的按下"与"同一个值")。
 * 按键只能在 Activity 最外层 onPreviewKeyEvent 可靠捕获(无焦点时事件只到根节点),
 * 故由 MainActivity 统一捕获后,以 [KeyHit] 分发给当前屏幕自行处理(focus-as-state)。
 */
/** 一次按键;longPress=true 表示 CENTER 长按(>=500ms 未松开),由 MainActivity 判定后分发。 */
data class KeyHit(val key: Key, val seq: Int, val longPress: Boolean = false)

/** 需要交给屏幕自行处理的导航/确认键(不含 MENU/BACK,那两键在 MainActivity 处理)。 */
val NAV_KEYS = setOf(
    Key(KeyEvent.KEYCODE_DPAD_UP),
    Key(KeyEvent.KEYCODE_DPAD_DOWN),
    Key(KeyEvent.KEYCODE_DPAD_LEFT),
    Key(KeyEvent.KEYCODE_DPAD_RIGHT),
    Key(KeyEvent.KEYCODE_DPAD_CENTER),
    Key(KeyEvent.KEYCODE_ENTER),
)

/** 常用按键([Key] 常量,按 code 相等比较)。 */
object K {
    val UP = Key(KeyEvent.KEYCODE_DPAD_UP)
    val DOWN = Key(KeyEvent.KEYCODE_DPAD_DOWN)
    val LEFT = Key(KeyEvent.KEYCODE_DPAD_LEFT)
    val RIGHT = Key(KeyEvent.KEYCODE_DPAD_RIGHT)
    val CENTER = Key(KeyEvent.KEYCODE_DPAD_CENTER)
    val ENTER = Key(KeyEvent.KEYCODE_ENTER)
}

/**
 * 系统输入法编辑模式:打开原生 IME 文本框时,根节点放行所有按键
 * (交给 Compose 焦点系统 + 系统屏幕键盘),退出编辑时恢复按键拦截分发。
 */
object ImeMode {
    @Volatile var active = false
}
