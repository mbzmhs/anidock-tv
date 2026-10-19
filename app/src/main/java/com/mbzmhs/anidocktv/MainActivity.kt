package com.mbzmhs.anidocktv

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.mbzmhs.anidocktv.data.AnimeEntity
import com.mbzmhs.anidocktv.data.SettingKeys
import com.mbzmhs.anidocktv.model.HomeFilters
import com.mbzmhs.anidocktv.ui.BackgroundController
import com.mbzmhs.anidocktv.ui.DetailScreen
import com.mbzmhs.anidocktv.ui.EngineSettingsScreen
import com.mbzmhs.anidocktv.ui.HomeScreen
import com.mbzmhs.anidocktv.ui.HomeTab
import com.mbzmhs.anidocktv.ui.SettingsScreen
import com.mbzmhs.anidocktv.ui.theme.AnidockTheme

/**
 * 导航与全局状态都在 composition 根:
 * - 首页(tab/筛选/分页/侧边栏)状态用 remember,切详情再回来不丢。
 * - 详情层/设置层各自 BackHandler 拦截返回键,回上一级而非退出。
 * - 菜单键(KEYCODE_MENU)在首页开/关侧边栏。
 */
class MainActivity : ComponentActivity() {
    private val selectedAnimeId = mutableStateOf<Long?>(null)
    private val showSettings = mutableStateOf(false)
    private val showEngineSettings = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureStoragePermission()   // 权限被撤销时补一次请求,防止下载落盘静默失败
        setContent {
            AnidockTheme {
                // 默认首页:按设置打开对应 tab(番剧列表/我的追番)
                var tab by remember { mutableStateOf(if (Services.homeTab == "FOLLOWED") HomeTab.FOLLOWED else HomeTab.LIST) }
                // 两 tab 各一套独立筛选记忆(启动从 Services 读回,变更时按当前 tab 回写,互不影响);page 启动一律回 1
                var listFilters by remember { mutableStateOf(Services.listFilters.copy(page = 1)) }
                var folFilters by remember { mutableStateOf(Services.folFilters.copy(page = 1)) }
                // 当前 tab 的筛选(传给首页);改筛选只动当前 tab 这套,不牵连另一个 tab
                val filters = if (tab == HomeTab.LIST) listFilters else folFilters
                var showSidebar by remember { mutableStateOf(false) }
                var sidebarMode by remember { mutableStateOf("main") }   // main / year / quarter / size / sort
                val scope = rememberCoroutineScope()

                fun goHomeTab(t: HomeTab) {
                    tab = t
                    if (t == HomeTab.LIST) listFilters = listFilters.copy(page = 1) else folFilters = folFilters.copy(page = 1)
                    showSidebar = false
                }
                // 侧边栏/分页改筛选:作用于当前 tab 这一套,并持久化到对应 key(不关闭侧边栏,方便继续改其他项;关闭交给 BACK/MENU)
                fun onFilters(f: HomeFilters) {
                    if (tab == HomeTab.LIST) { listFilters = f; scope.launch { Services.repo.set(SettingKeys.LIST_FILTERS, f.toJson()) } }
                    else { folFilters = f; scope.launch { Services.repo.set(SettingKeys.FOL_FILTERS, f.toJson()) } }
                }

                // 侧边栏打开时,返回键:组合框子级(年/季/每页)先退回主列表,主列表才关闭侧边栏。
                // 仅在停留在首页(未进详情/设置)时生效,避免与详情/设置各自的返回处理冲突。
                BackHandler(enabled = showSidebar && selectedAnimeId.value == null && !showSettings.value) {
                    if (sidebarMode != "main") sidebarMode = "main"
                    else showSidebar = false
                }

                // 按键只在 Activity 最外层 onPreviewKeyEvent 可靠捕获(无焦点时事件只到根节点)。
                // MENU 开/关侧边栏;BACK 交给 BackHandler;D-pad/确认键打包成 KeyHit 分发给当前屏幕。
                val seqRef = remember { intArrayOf(0) }
                var keyHit by remember { mutableStateOf(com.mbzmhs.anidocktv.ui.tv.KeyHit(Key(android.view.KeyEvent.KEYCODE_UNKNOWN), 0)) }
                // CENTER 长按判定:首次 KeyDown 起 500ms 计时,未松开 → 长按;提前 KeyUp → 取消计时并按单击分发。
                // 遥控器长按是连续自动重复 KeyDown:按住期间(centerDown=true)的重复一律忽略,
                // 绝不 cancel+重启动画——旧实现按"时间差<400ms 算重复"判定,重复间隔落在 400-500ms
                // 时每个重复都会掐掉计时器,长按永远到不了 500ms。
                val centerLongJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                val centerLongFired = remember { mutableStateOf(false) }
                val centerDown = remember { mutableStateOf(false) }
                // 根节点必须 focusable,否则窗口拿不到输入焦点,onPreviewKeyEvent 不触发。
                // 焦点锁定在根节点(唯一 focusable),按键全部在此捕获后手动分发(focus-as-state)。
                // TV 上系统会自动给焦点;非 TV 形态(模拟器)不会,必须显式请求。
                val rootFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                androidx.compose.runtime.LaunchedEffect(Unit) { rootFocusRequester.requestFocus() }
                val rootModifier = Modifier
                    .focusable()
                    .focusRequester(rootFocusRequester)
                    .onPreviewKeyEvent { ke ->
                    // 原生输入法编辑中:全部放行给焦点系统/IME,不手动分发
                    if (com.mbzmhs.anidocktv.ui.tv.ImeMode.active) return@onPreviewKeyEvent false
                    if (ke.key == Key(android.view.KeyEvent.KEYCODE_MENU)) {
                        if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (selectedAnimeId.value == null && !showSettings.value) {
                            showSidebar = !showSidebar
                            if (showSidebar) sidebarMode = "main"   // 每次打开侧边栏都回到主列表
                            true
                        } else false
                    } else if (ke.key in com.mbzmhs.anidocktv.ui.tv.NAV_KEYS) {
                        if (ke.key == com.mbzmhs.anidocktv.ui.tv.K.CENTER) {
                            when (ke.type) {
                                KeyEventType.KeyDown -> {
                                    if (centerDown.value) {
                                        // 按住期间的自动重复:忽略,不重启动画
                                    } else {
                                        centerDown.value = true
                                        centerLongFired.value = false
                                        centerLongJob.value?.cancel()
                                        centerLongJob.value = scope.launch {
                                            kotlinx.coroutines.delay(500)
                                            centerLongFired.value = true
                                            seqRef[0]++
                                            keyHit = com.mbzmhs.anidocktv.ui.tv.KeyHit(ke.key, seqRef[0], longPress = true)
                                        }
                                    }
                                }
                                else -> { // KeyUp:500ms 内松开 = 单击
                                    centerDown.value = false
                                    centerLongJob.value?.cancel()
                                    if (!centerLongFired.value) {
                                        seqRef[0]++
                                        keyHit = com.mbzmhs.anidocktv.ui.tv.KeyHit(ke.key, seqRef[0])
                                    }
                                }
                            }
                        } else {
                            centerLongJob.value?.cancel()
                            if (ke.type == KeyEventType.KeyDown) {
                                seqRef[0]++
                                keyHit = com.mbzmhs.anidocktv.ui.tv.KeyHit(ke.key, seqRef[0])
                            }
                        }
                        true
                    } else false
                }

                // HomeScreen 常驻底层,详情/设置作为覆盖层叠在上面:进详情/设置再返回,首页状态(列表/焦点/滚动)不丢
                androidx.compose.foundation.layout.Box(modifier = rootModifier) {
                    BackgroundController()   // 背景图解码+全局缓存(compose 一次,各页复用,消除背景"事后弹出"闪烁)
                    HomeScreen(
                        tab = tab,
                        filters = filters,
                        showSidebar = showSidebar,
                        sidebarMode = sidebarMode,
                        onSidebarMode = { sidebarMode = it },
                        active = selectedAnimeId.value == null && !showSettings.value,
                        onTab = ::goHomeTab,
                        onFilters = ::onFilters,
                        onAnimeClick = { a ->
                            // 记录点开时的总集数:追番卡片据此显示未读蓝点(当前集数>记录值),点开后消失
                            scope.launch { Services.repo.markSeen(a) }
                            selectedAnimeId.value = a.anidockId
                        },
                        onFollow = { a, f -> scope.launch { Services.repo.setFollowed(a, f) } },
                        onSettings = { showSettings.value = true; showSidebar = false },
                        keyHit = keyHit,
                    )
                    when {
                        showEngineSettings.value -> EngineSettingsScreen(onBack = { showEngineSettings.value = false }, keyHit = keyHit)
                        showSettings.value -> SettingsScreen(onBack = { showSettings.value = false }, onEngineSettings = { showEngineSettings.value = true }, keyHit = keyHit)
                        selectedAnimeId.value != null -> DetailScreen(animeId = selectedAnimeId.value!!, onBack = { selectedAnimeId.value = null }, onTagSearch = { tag -> tab = HomeTab.LIST; listFilters = listFilters.copy(search = tag, page = 1); scope.launch { Services.repo.set(SettingKeys.LIST_FILTERS, listFilters.toJson()) }; selectedAnimeId.value = null }, keyHit = keyHit, fromFollowed = (tab == HomeTab.FOLLOWED))
                    }
                }
            }
        }
    }

    /**
     * 存储权限自检:引擎直写共享存储下载目录依赖 WRITE_EXTERNAL_STORAGE。
     * 用户在系统设置里关掉它后 app 不会收到任何通知,下载落盘会静默失败,
     * 所以启动时若发现未授予就重新弹一次系统授权框(用户勾过"不再询问"则系统静默拒绝,不骚扰)。
     */
    private val storagePermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Log.d("AnidockTV", "perm: 存储权限已(重新)授予,共享存储落盘恢复")
            else {
                Log.e("AnidockTV", "perm: 存储权限仍被拒绝,下载文件无法写入共享目录")
                android.widget.Toast.makeText(this, "存储权限未授予:下载完成后的文件搬运会失败", android.widget.Toast.LENGTH_LONG).show()
            }
        }

    private fun ensureStoragePermission() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        Log.d("AnidockTV", "perm: 启动时发现存储权限缺失,重新请求")
        storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
