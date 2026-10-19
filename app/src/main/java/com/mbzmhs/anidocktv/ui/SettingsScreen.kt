package com.mbzmhs.anidocktv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.data.SettingKeys
import com.mbzmhs.anidocktv.data.UsbAccess
import com.mbzmhs.anidocktv.ui.theme.Anidock
import com.mbzmhs.anidocktv.ui.tv.ImeMode
import com.mbzmhs.anidocktv.ui.tv.K
import com.mbzmhs.anidocktv.ui.tv.KeyHit
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页(focus-as-state,按键由 MainActivity 根节点捕获后经 keyHit 分发):
 * - 文本项(地址/密码/端口):系统原生输入法编辑(屏幕键盘),保存/取消按钮确认
 * - 下载根目录:文件夹浏览选择(不用手填路径)
 * - BACK:覆盖层打开时先关覆盖层,否则回首页
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onEngineSettings: () -> Unit = {}, keyHit: KeyHit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rowIdx by remember { mutableStateOf(0) }
    // 文本编辑(系统输入法)或目录选择,二选一覆盖层
    var textEdit by remember { mutableStateOf<TextEditSpec?>(null) }
    var pickDir by remember { mutableStateOf<String?>(null) }
    var pickIdx by remember { mutableStateOf(0) }
    // 背景图选择:pickBg = 正在浏览的目录
    var pickBg by remember { mutableStateOf<String?>(null) }
    var bgIdx by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    // U 盘目录需一次性系统授权:记下待授权目录,选择器返回后自动复检并保存
    var pendingUsbDir by remember { mutableStateOf<String?>(null) }
    var usbAuthTick by remember { mutableStateOf(0) }

    val usbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            res.data?.data?.let { uri ->
                runCatching { UsbAccess.takePersist(context, uri) }
                    .onSuccess {
                        val pending = pendingUsbDir
                        pendingUsbDir = null
                        if (pending != null) {
                            if (UsbAccess.placeable(context, File(pending))) {
                                Services.downloadDir = pending
                                scope.launch { Services.repo.set(SettingKeys.DOWNLOAD_DIR, pending) }
                                statusMsg = "U盘授权成功,下载目录已保存:$pending"
                            } else {
                                statusMsg = "U盘授权成功,但该目录仍不可写,请重选"
                            }
                        } else {
                            statusMsg = "U盘授权成功"
                        }
                    }
                    .onFailure { statusMsg = "U盘授权失败:${it.message}" }
            } ?: run {
                pendingUsbDir = null
                statusMsg = "未选择文件夹"
            }
        } else {
            pendingUsbDir = null
            statusMsg = "已取消U盘授权"
        }
        usbAuthTick++
    }

    // 记录打开本页时的按键序号:打开设置的那一次 CENTER 不能泄漏进来(否则直接弹编辑器)
    val initialSeq = remember { keyHit.seq }

    // 下载目录可写需要外置存储权限(targetSdk 28 走传统权限;BRAVIA 授予后直写 /storage/emulated)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) statusMsg = "未授予外置存储权限,下载目录可能不可写"
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    // 系统文件选择器(ACTION_OPEN_DOCUMENT,单张图片):有 DocumentsUI 的设备可用。
    // 选中的 content URI 一次性授权不可直读,复制到 app 私有目录再使用(与 U 盘授权无关)。
    val sysDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            statusMsg = "未选择图片"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.readBytes()
                val name = (uri.lastPathSegment ?: "background").substringBeforeLast('.')
                val ext = uri.lastPathSegment?.substringAfterLast('.', "png")?.lowercase() ?: "png"
                val dest = File(context.filesDir, "bg_$name.$ext")
                dest.writeBytes(bytes)
                withContext(Dispatchers.IO) {
                    if (loadBackground(dest.absolutePath) == null) error("图片解码失败")
                }
                Services.bgImage = dest.absolutePath
                BackgroundUi.path.value = dest.absolutePath
                Services.repo.set(SettingKeys.BG_IMAGE, dest.absolutePath)
                statusMsg = "已应用背景图(来自系统选择器)"
            }.onFailure { statusMsg = "应用背景图失败:${it.message}" }
        }
    }
    // 本设备是否有系统文件选择器;无则该项禁用并提示用目录浏览
    val sysDocAvailable = remember {
        runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_DEFAULT), 0,
            ).isNotEmpty()
        }.getOrDefault(false)
    }

    // 页面销毁时确保退出 IME 放行模式
    DisposableEffect(Unit) { onDispose { ImeMode.active = false } }

    LaunchedEffect(statusMsg) {
        if (statusMsg != null) {
            delay(3000)
            statusMsg = null
        }
    }

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    BackHandler { onBack() }
    BackHandler(enabled = textEdit != null || pickDir != null || pickBg != null || confirmClear) {
        if (textEdit != null) {
            ImeMode.active = false
            textEdit = null   // 手动输入路径关闭后回到选择器
        } else {
            pickDir = null
            pickBg = null
            confirmClear = false
        }
    }

    fun openTextEdit(spec: TextEditSpec) {
        textEdit = spec
        ImeMode.active = true
    }

    fun openDirPick() {
        val start = File(Services.downloadDir).takeIf { it.exists() }?.absolutePath ?: "/storage/emulated/0"
        pickDir = start
        pickIdx = 0
    }

    fun confirmDir(dir: String) {
        val d = File(dir)
        if (UsbAccess.placeable(context, d)) {
            pickDir = null
            Services.downloadDir = dir
            scope.launch { Services.repo.set(SettingKeys.DOWNLOAD_DIR, dir) }
            statusMsg = if (UsbAccess.probeDirect(d)) "已保存:$dir" else "已保存:$dir(U盘,经系统授权写入)"
        } else if (UsbAccess.pickerAvailable(context)) {
            // U 盘目录:先走一次性系统授权(选择器直接定位到该处,用户只需确认)
            pickDir = null
            pendingUsbDir = dir
            usbLauncher.launch(UsbAccess.pickerIntent(UsbAccess.treeUriForPath(context, dir)))
        } else {
            statusMsg = "该目录不可用(U盘不可写,且本电视无系统文件选择器可供授权)。请改用内部存储预设,或手动输入 /storage/emulated/0/ 下的路径"
        }
    }

    /** 打开背景图选择器:从当前背景图所在目录(或 Pictures 预设)开始浏览。 */
    fun openBgPick() {
        val from = Services.bgImage
            .takeIf { it.isNotEmpty() }
            ?.let { File(it).parentFile?.absolutePath }
            ?.takeIf { d -> d != null && File(d).isDirectory }
        pickBg = from ?: File("/storage/emulated/0/Pictures").takeIf { it.isDirectory }?.absolutePath ?: "/storage/emulated/0"
        bgIdx = 0
    }

    /** 应用背景图:IO 线程解码校验,成功才写入设置(避免把打不开的路径存进去)。 */
    fun confirmBgImage(path: String) {
        pickBg = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { loadBackground(path) != null }
            if (ok) {
                Services.bgImage = path
                BackgroundUi.path.value = path
                Services.repo.set(SettingKeys.BG_IMAGE, path)
                statusMsg = "已应用背景图:$path"
            } else {
                statusMsg = "无法应用背景图:文件不存在或格式不支持(jpg/png/webp/bmp)"
            }
        }
    }

    fun clearBgImage() {
        pickBg = null
        Services.bgImage = ""
        BackgroundUi.path.value = ""
        scope.launch { Services.repo.set(SettingKeys.BG_IMAGE, "") }
        statusMsg = "已清除背景图"
    }

    // 背景图选择项:与目录选择同理,但额外列出当前目录下的图片文件,
    // 并附"系统选择器 / 手动输入 / 清除背景图"快捷项。
    val bgItems = remember(pickBg) {
        val cur = pickBg ?: return@remember emptyList()
        val parent = File(cur).parentFile?.absolutePath
        val entries = runCatching { File(cur).listFiles() }.getOrNull().orEmpty()
        val dirs = entries.filter { it.isDirectory }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val imgs = entries.filter { it.isFile && BG_IMAGE_EXTS.any { e -> it.name.lowercase().endsWith(e) } }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        buildList {
            add(PickItem("系统文件选择器", if (sysDocAvailable) PickItem.SYSDOC else PickItem.SYSDOC_DISABLED, disabled = !sysDocAvailable, shortcut = true))
            if (parent != null) add(PickItem("上一级 $parent", PickItem.UP, target = parent))
            dirs.forEach { add(PickItem(it.name, PickItem.DIR, target = it.absolutePath)) }
            imgs.forEach { add(PickItem(it.name, PickItem.IMG, target = it.absolutePath)) }
            listOf("/storage/emulated/0/Pictures", "/storage/emulated/0/DCIM", "/storage/emulated/0/Download")
                .filter { it != cur && File(it).exists() }
                .forEach { add(PickItem("预设 · ${it.removePrefix("/storage/emulated/0")}", PickItem.PRESET, target = it, shortcut = true)) }
            add(PickItem("手动输入路径…", PickItem.MANUAL, shortcut = true))
            if (Services.bgImage.isNotEmpty()) add(PickItem("清除背景图", PickItem.CLEAR, shortcut = true))
        }
    }

    // 焦点永不落在禁用项上:系统选择器不可用时它是第 0 项,而 openBgPick/进目录/回上级都把
    // bgIdx 硬置 0 → 默认焦点停在禁用项,且 moveBgRow 从禁用项起步会把它当"起点锚点"上移到
    // 首个可聚焦项再走一步,等于把"上一级"跳过。每次换目录(pickBg 变)都把焦点落到首个可聚焦项,
    // 覆盖所有入口;↑↓ 移动只改 bgIdx 不触发本 effect,不受影响。
    LaunchedEffect(pickBg) {
        val first = (0 until bgItems.size).firstOrNull { !bgItems[it].disabled } ?: 0
        if (bgIdx != first) bgIdx = first
    }

    /** 背景图选择器内上下移动焦点,跳过禁用项,首尾环绕。 */
    fun moveBgRow(delta: Int) {
        val items = bgItems
        val n = items.size
        if (n == 0) return
        var from = if (bgIdx < n && !items[bgIdx].disabled) bgIdx else (0 until n).firstOrNull { !items[it].disabled } ?: 0
        for (step in 1..n) {
            val cand = ((from + delta * step) % n + n) % n
            if (!items[cand].disabled) { bgIdx = cand; return }
        }
    }

    fun finishTextEdit(value: String?) {
        ImeMode.active = false
        val spec = textEdit
        textEdit = null
        if (spec == null || value == null) return
        when (spec.kind) {
            "url" -> {
                if (value.isEmpty()) { statusMsg = "地址不能为空"; return }
                val v = if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
                Services.api.setBaseUrl(v)
                Services.api.token = ""   // 地址变了,强制下次刷新重新登录
                Services.repo.resetLoginError()
                scope.launch { Services.repo.set(SettingKeys.BASE_URL, v) }
                statusMsg = "已保存,下次刷新自动重新登录"
            }
            "password" -> {
                if (value.isEmpty()) { statusMsg = "密码不能为空"; return }
                Services.visitorPassword = value
                Services.api.token = ""
                Services.repo.resetLoginError()
                scope.launch { Services.repo.set(SettingKeys.VISITOR_PASSWORD, value) }
                statusMsg = "已保存,下次登录生效"
            }
            "port" -> {
                val p = value.toIntOrNull() ?: run { statusMsg = "端口必须是数字"; return }
                if (p !in 1024..65535) { statusMsg = "端口需在 1024-65535 之间"; return }
                Services.enginePort = p
                scope.launch { Services.repo.set(SettingKeys.ENGINE_PORT, p.toString()) }
                statusMsg = "已保存"
            }
            "path" -> {
                if (value.isEmpty()) { statusMsg = "路径不能为空"; return }
                confirmDir(value.trim())
            }
            "bgpath" -> {
                if (value.isEmpty()) { statusMsg = "路径不能为空"; return }
                confirmBgImage(value.trim())
            }
            "opacity" -> {
                val n = value.toIntOrNull() ?: run { statusMsg = "不透明度必须是数字"; return }
                if (n !in 0..100) { statusMsg = "不透明度需在 0-100 之间"; return }
                Services.bgOpacity = n
                BackgroundUi.opacity.value = n / 100f
                scope.launch { Services.repo.set(SettingKeys.BG_OPACITY, n.toString()) }
                statusMsg = "已保存"
            }
        }
    }

    // 目录选择项:使用此目录 / 内部存储预设(emulated 不可列目录,必须预设或手动输入) / 手动输入 / ..上一级 / 子目录
    val pickItems = remember(pickDir) {
        val cur = pickDir ?: return@remember emptyList()
        val parent = File(cur).parentFile?.absolutePath
        // 顺序:使用/上一级/子目录在前(浏览主体),预设与 U 盘卷快捷项、手动输入垫底。
        // 导航不设限:任何目录都可聚焦进入;"能否当下载目录"只在"使用此目录"时判断
        // (不可直写的 U 盘目录会转系统授权,无选择器的设备给出提示)
        val items = buildList {
            add(PickItem("使用此目录", PickItem.USE))
            if (parent != null) add(PickItem("上一级 $parent", PickItem.UP, target = parent))
            runCatching { File(cur).listFiles() }
                .getOrNull()
                ?.filter { it.isDirectory }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?.forEach { add(PickItem(it.name, PickItem.DIR, target = it.absolutePath)) }
            listOf("/storage/emulated/0/Download", "/storage/emulated/0/Download/anidock")
                .filter { it != cur }
                .forEach { add(PickItem("预设 · 内部存储${it.removePrefix("/storage/emulated/0")}", PickItem.PRESET, target = it, shortcut = true)) }
            // U 盘卷根快捷项(授权后可选,未授权选中时会先弹系统授权)
            UsbAccess.removableVolumes(context).forEach { v ->
                runCatching { v.javaClass.getMethod("getPath").invoke(v) as String }
                    .getOrNull()
                    ?.let { p -> add(PickItem("U盘卷 · ${p.removePrefix("/storage/")}", PickItem.DIR, target = p, shortcut = true)) }
            }
            add(PickItem("手动输入路径…", PickItem.MANUAL, shortcut = true))
        }
        items
    }

    /** 上下移动焦点,跳过不可写(禁用)项,首尾环绕。 */
    fun moveRow(delta: Int) {
        val items = pickItems
        val n = items.size
        if (n == 0) return
        var from = if (pickIdx < n && !items[pickIdx].disabled) pickIdx else (0 until n).firstOrNull { !items[it].disabled } ?: 0
        for (step in 1..n) {
            val cand = ((from + delta * step) % n + n) % n
            if (!items[cand].disabled) { pickIdx = cand; return }
        }
    }

    // 缓存使用量(孤儿半成品 + 图片缓存,不含进行中任务的活文件);清理后重算
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheBytes = Services.repo.cacheUsage()
    }

    fun handleKey(key: Key) {
        if (confirmClear) {
            if (key == K.CENTER || key == K.ENTER) {
                confirmClear = false
                scope.launch {
                    Services.repo.wipeFollowed()
                    statusMsg = "已清除本地追番数据与下载记录"
                }
            }
            return
        }
        if (textEdit != null) return   // IME 编辑中:按键已放行给系统,这里不处理(目录选择器的手动输入也走这里)
        if (pickDir != null) {
            val item = pickItems.getOrNull(pickIdx)
            if (item == null) return
            when (key) {
                K.UP -> moveRow(-1)
                K.DOWN -> moveRow(1)
                K.LEFT -> {
                    // 在子目录行按左 = 退回上一级
                    if (item.kind == PickItem.DIR) {
                        val target = item.target
                        if (target != null) {
                            File(target).parentFile?.absolutePath?.let { pickDir = it; pickIdx = 0 }
                        }
                    }
                }
                K.RIGHT -> {
                    if (item.kind == PickItem.DIR && !item.disabled) {
                        val target = item.target
                        if (target != null) { pickDir = target; pickIdx = 0 }
                    }
                }
                K.CENTER, K.ENTER -> when (item.kind) {
                    PickItem.USE -> pickDir?.let { confirmDir(it) }
                    PickItem.PRESET -> item.target?.let { confirmDir(it) }
                    PickItem.MANUAL -> openTextEdit(
                        TextEditSpec("path", "下载目录路径", pickDir ?: "", false, false),
                    )
                    PickItem.UP -> item.target?.let { pickDir = it; pickIdx = 0 }
                    PickItem.DIR -> if (!item.disabled) item.target?.let { pickDir = it; pickIdx = 0 }
                }
            }
            return
        }
        if (pickBg != null) {
            val item = bgItems.getOrNull(bgIdx)
            if (item == null) return
            when (key) {
                K.UP -> moveBgRow(-1)
                K.DOWN -> moveBgRow(1)
                K.LEFT -> {
                    // 在子目录行按左 = 退回上一级(与目录选择器一致)
                    if (item.kind == PickItem.DIR) {
                        item.target?.let { t ->
                            File(t).parentFile?.absolutePath?.let { p -> pickBg = p; bgIdx = 0 }
                        }
                    }
                }
                K.RIGHT -> {
                    if (item.kind == PickItem.DIR) {
                        item.target?.let { pickBg = it; bgIdx = 0 }
                    }
                }
                K.CENTER, K.ENTER -> when (item.kind) {
                    PickItem.IMG -> item.target?.let { confirmBgImage(it) }
                    PickItem.PRESET, PickItem.UP, PickItem.DIR -> {
                        item.target?.let { t ->
                            if (File(t).isDirectory) { pickBg = t; bgIdx = 0 }
                        }
                    }
                    PickItem.MANUAL -> openTextEdit(
                        TextEditSpec("bgpath", "背景图路径", pickBg ?: "", false, false),
                    )
                    PickItem.SYSDOC -> if (sysDocAvailable) sysDocLauncher.launch(arrayOf("image/*"))
                    PickItem.CLEAR -> clearBgImage()
                }
            }
            return
        }
        when (key) {
            K.UP -> rowIdx = (rowIdx + ROW_COUNT - 1) % ROW_COUNT
            K.DOWN -> rowIdx = (rowIdx + 1) % ROW_COUNT
            K.CENTER, K.ENTER -> when (rowIdx) {
                0 -> openTextEdit(TextEditSpec("url", "服务器地址", Services.api.currentBaseUrl, false, false))
                1 -> openTextEdit(TextEditSpec("password", "访客密码", "", true, false))
                2 -> openDirPick()
                3 -> openTextEdit(TextEditSpec("port", "引擎端口", Services.enginePort.toString(), false, true))
                4 -> onEngineSettings()   // 进入引擎设置子页(限速/并发/BT 开关)
                5 -> {
                    if (!UsbAccess.pickerAvailable(context)) {
                        statusMsg = "本电视无系统文件选择器,U盘目录无法用作下载目录。已下载的文件保留在原目录,可用文件管理器移动"
                    } else {
                        usbLauncher.launch(UsbAccess.pickerIntent())
                    }
                }
                6 -> confirmClear = true
                7 -> scope.launch {
                    statusMsg = Services.repo.cleanCache()
                    cacheBytes = Services.repo.cacheUsage()
                }
                8 -> {
                    // 切换默认首页:番剧列表 <-> 我的追番
                    val next = if (Services.homeTab == "FOLLOWED") "LIST" else "FOLLOWED"
                    Services.homeTab = next
                    scope.launch { Services.repo.set(SettingKeys.HOME_TAB, next) }
                    statusMsg = "默认首页已设为:${if (next == "FOLLOWED") "我的追番" else "番剧列表"}(下次启动生效)"
                }
                9 -> openBgPick()
                10 -> openTextEdit(TextEditSpec("opacity", "背景图不透明度(0-100)", Services.bgOpacity.toString(), false, true))
                11 -> {}   // 关于:无操作
            }
        }
    }

    LaunchedEffect(keyHit.seq) {
        if (keyHit.seq > initialSeq) handleKey(keyHit.key)
    }

    val pwdMask = "•".repeat(8)
    val dlDir = Services.downloadDir
    val dlDirWarn = remember(dlDir, usbAuthTick) { dlDir.isNotEmpty() && !UsbAccess.placeable(context, File(dlDir)) }
    // U 盘授权状态(选择器返回后 usbAuthTick 变化 → 重查)
    val usbState = remember(usbAuthTick, context) {
        if (!UsbAccess.pickerAvailable(context)) null
        else UsbAccess.authorizedTrees(context).firstOrNull()?.let { UsbAccess.treeLabel(context, it) }
    }
    // 引擎运行状态(IO 探测,不卡主线程)
    var engineOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            engineOn = com.mbzmhs.anidocktv.data.EngineService.isRunning()
        }
    }
    // 设置项按逻辑分组(分区标题 + 设置项交错);可聚焦项的顺序/下标保持不变(0-11),
    // 供 handleKey 的 when(rowIdx) 沿用;分组标题只是视觉层级,不参与按键分发。
    val display = listOf(
        SettingDisplay.Section("连接"),
        SettingDisplay.Item(SettingRowDef("服务器地址", Services.api.currentBaseUrl, "回车编辑")),
        SettingDisplay.Item(SettingRowDef("访客密码", pwdMask, "回车编辑")),
        SettingDisplay.Section("下载"),
        SettingDisplay.Item(SettingRowDef("下载根目录", if (dlDirWarn) "$dlDir ⚠不可写" else dlDir, if (dlDirWarn) "不可写,回车更换" else "回车选择目录")),
        SettingDisplay.Item(SettingRowDef("引擎端口", "${Services.enginePort} (127.0.0.1)", "回车编辑")),
        SettingDisplay.Item(SettingRowDef("下载引擎", if (engineOn) "运行中" else "未启动(随 app 自启)", "回车 = 引擎设置")),
        SettingDisplay.Section("存储与数据"),
        SettingDisplay.Item(SettingRowDef(
            "U盘访问",
            usbState ?: "不支持(本电视无文件选择器)",
            if (usbState != null) "已授权,回车重选" else "使用 U盘作为下载目录的前提",
        )),
        SettingDisplay.Item(SettingRowDef("清除本地数据", "", "追番列表 + 下载记录(已下载文件不受影响)")),
        SettingDisplay.Item(SettingRowDef(
            "清理缓存",
            "${cacheBytes / 1024 / 1024} MB(未完成下载的残留文件 + 图片缓存)",
            "回车清理;进行中的任务不受影响",
        )),
        SettingDisplay.Section("外观"),
        SettingDisplay.Item(SettingRowDef("默认首页", if (Services.homeTab == "FOLLOWED") "我的追番" else "番剧列表", "回车切换")),
        SettingDisplay.Item(SettingRowDef("背景图", if (Services.bgImage.isEmpty()) "未设置" else Services.bgImage, "回车选择")),
        SettingDisplay.Item(SettingRowDef("背景图不透明度", "${Services.bgOpacity}%", "回车编辑")),
        SettingDisplay.Section("关于"),
        SettingDisplay.Item(SettingRowDef("关于", "v$version", "AniDock TV · 数据全部本地")),
    )

    // 行多时垂直滚动,焦点行滚入可见区。
    // 分区标题也占高度,故按 display 逐段累加像素,算出每个可聚焦行真实的 top(px)。
    val settingsScroll = rememberScrollState()
    val visH = remember { mutableStateOf(0) }
    val density = LocalDensity.current
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
    LaunchedEffect(rowIdx, visH.value, fieldTops) {
        val vh = visH.value
        if (vh <= 0) return@LaunchedEffect
        val fieldH = with(density) { 56.dp.roundToPx() }
        val top = fieldTops.getOrElse(rowIdx) { 0 }
        val cur = settingsScroll.value
        val target = when {
            top < cur -> top
            top + fieldH > cur + vh -> (top + fieldH - vh).coerceAtLeast(0)
            else -> cur
        }
        if (target != cur) settingsScroll.scrollTo(target.coerceIn(0, settingsScroll.maxValue))
    }
    // 每个 display 元素对应的"可聚焦行下标"(分区标题 = -1),用于渲染时判断哪一行获焦。
    val focusableIndex = remember(display) {
        var i = 0
        display.map { if (it is SettingDisplay.Item) i++ else -1 }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Anidock.Bg))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 20.dp),
        ) {
        if (textEdit == null && pickDir == null) {
            Text("设置", color = Anidock.Text, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { visH.value = it.height }
                    .verticalScroll(settingsScroll),
            ) {
                display.forEachIndexed { i, child ->
                    when (child) {
                        is SettingDisplay.Section -> SectionHeader(child.title)
                        is SettingDisplay.Item -> SettingRowView(child.def, focused = focusableIndex[i] == rowIdx)
                    }
                }
            }
        }
        if (statusMsg != null) {
            Text(statusMsg!!, color = Anidock.Accent, style = MaterialTheme.typography.bodyMedium)
        }
        }
    }

    // 覆盖层:系统输入法编辑 / 目录选择 / 清除确认
    // 覆盖层:目录选择 / 背景图选择 / 清除确认 / 系统输入法编辑(顶层,必须最后渲染)。
    // 手动输入从选择器内弹出时,pickDir/pickBg 仍非空,textEdit 放最后才不会被选择器盖住(旧版被盖住导致看不见)。
    pickDir?.let { dir ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Anidock.Card,
                modifier = Modifier
                    .width(560.dp)
                    .height(560.dp)
                    .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择下载目录", color = Anidock.Text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    PathBox(dir)
                    DirPickList(items = pickItems, pickIdx = pickIdx)
                    if (statusMsg != null) {
                        Text(statusMsg!!, color = Anidock.Accent, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("回车:使用/进入目录,或选快捷入口。U盘目录需先经系统授权(仅需确认)", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    pickBg?.let { dir ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Anidock.Card,
                modifier = Modifier
                    .width(560.dp)
                    .height(560.dp)
                    .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择背景图", color = Anidock.Text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    PathBox(dir)
                    DirPickList(items = bgItems, pickIdx = bgIdx)
                    if (statusMsg != null) {
                        Text(statusMsg!!, color = Anidock.Accent, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("回车:选择图片文件或进入目录。支持 jpg/png/webp/bmp", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (confirmClear) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Anidock.Card,
                modifier = Modifier
                    .width(420.dp)
                    .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("清除本地追番数据与下载记录?", color = Anidock.Text, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("已下载的文件不受影响。", color = Anidock.Text3, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("回车=确认    BACK=取消", color = Anidock.Text2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    textEdit?.let { spec ->
        TextEditOverlay(
            spec = spec,
            onDone = { v -> finishTextEdit(v) },
        )
    }
}

private const val ROW_COUNT = 12

/**
 * 一行设置项。
 * - toggle == null:普通行(label + value + hint);
 * - toggle != null:开关行,右侧渲染真滑块,value 被忽略(仍保留 hint 如「回车切换」)。
 */
internal data class SettingRowDef(
    val label: String,
    val value: String,
    val hint: String,
    val toggle: Boolean? = null,
)

/** 设置页/引擎页的可渲染元素:分区标题(不可聚焦)与设置项(可聚焦)交错,提供视觉层级。 */
internal sealed class SettingDisplay {
    data class Section(val title: String) : SettingDisplay()
    data class Item(val def: SettingRowDef) : SettingDisplay()
}

/** 文本编辑规格:kind = url / password / port / path / number。decimal 允许小数(分享率等)。 */
internal data class TextEditSpec(
    val kind: String,
    val label: String,
    val initial: String,
    val password: Boolean,
    val digits: Boolean,
    val decimal: Boolean = false,
)

/** 原生输入法编辑覆盖层:文本框自动聚焦拉起系统屏幕键盘。 */
@Composable
internal fun TextEditOverlay(spec: TextEditSpec, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(spec.initial) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .width(560.dp)
                .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(spec.label, color = Anidock.Text, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = when {
                            spec.digits -> v.filter { it.isDigit() }
                            spec.decimal -> v.filter { it.isDigit() || it == '.' }
                            else -> v
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (spec.password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            spec.digits -> KeyboardType.Number
                            spec.decimal -> KeyboardType.Decimal
                            spec.password -> KeyboardType.Password
                            else -> KeyboardType.Uri
                        }
                    ),
                    // 键盘上的"完成/回车"键 = 直接保存(TV 上最顺的确认路径)
                    keyboardActions = KeyboardActions(onDone = { onDone(text.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("键盘「完成/回车」= 保存;BACK = 取消(输入丢失)", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 目录选择项。kind: use=使用此目录 / preset=内部存储预设 / manual=手动输入 / up=上一级 / dir=子目录。 */
private class PickItem(
    val label: String,
    val kind: String,
    val target: String? = null,
    val disabled: Boolean = false,
    val shortcut: Boolean = false,   // 快捷入口(预设/U盘卷/手动输入),与真实子目录视觉区分
) {
    companion object {
        const val USE = "use"
        const val PRESET = "preset"
        const val MANUAL = "manual"
        const val UP = "up"
        const val DIR = "dir"
        // 背景图选择器专用
        const val IMG = "img"                 // 图片文件,回车 = 应用
        const val SYSDOC = "sysdoc"           // 系统文件选择器
        const val SYSDOC_DISABLED = "sysdoc_disabled"
        const val CLEAR = "clear"             // 清除背景图
    }
}

/** 目录列表(固定高度视口,可滚动,焦点行自动滚入可见区)。 */
private const val PICK_LIST_H = 340        // 视口高度 dp(约 7-8 行)
private const val PICK_ROW_H = 44          // 行高 40 + 间距 4
private const val PICK_DIV_H = 26          // 「快捷入口」分隔块(标签 22 + 间距 4),焦点定位偏移用

@Composable
private fun DirPickList(items: List<PickItem>, pickIdx: Int) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val firstShortcut = items.indexOfFirst { it.shortcut }   // -1 = 无快捷区
    // 列表内容变了(进目录),回卷到顶
    LaunchedEffect(items) { scroll.scrollTo(0) }
    LaunchedEffect(pickIdx) {
        val rowPx = with(density) { PICK_ROW_H.dp.roundToPx() }
        val divPx = with(density) { PICK_DIV_H.dp.roundToPx() }
        val vpPx = with(density) { PICK_LIST_H.dp.roundToPx() }
        // 焦点行落在快捷区(分隔块之后)时,滚动位置要加上分隔块的高度
        val divOffset = if (firstShortcut >= 0 && pickIdx >= firstShortcut) divPx else 0
        val top = pickIdx * rowPx + divOffset
        val cur = scroll.value
        val target = when {
            top < cur -> top
            top + rowPx > cur + vpPx -> (top + rowPx - vpPx).coerceAtLeast(0)
            else -> cur
        }
        if (target != cur) scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(PICK_LIST_H.dp)
            .verticalScroll(scroll),
    ) {
        items.forEachIndexed { i, it ->
            if (i == firstShortcut) ShortcutDivider()   // 非聚焦分隔 + 「快捷入口」标签
            PickRow(it, focused = i == pickIdx)
        }
    }
}

/** 「使用此目录」主操作行:accent 按钮样式,与普通浏览行区分(高度同普通行,保证滚动定位一致)。 */
@Composable
private fun PickButtonRow(label: String, focused: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(width = if (focused) 2.dp else 1.dp, color = Anidock.Accent, shape = RoundedCornerShape(8.dp))
            .background(Anidock.Accent.copy(alpha = if (focused) 0.24f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Check, null, tint = if (focused) Color.White else Anidock.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = if (focused) Color.White else Anidock.Accent, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** 「快捷入口」分隔块:标签 + 横线(非聚焦,占 PICK_DIV_H 高度供滚动定位)。 */
@Composable
private fun ShortcutDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("快捷入口", color = Anidock.Accent, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Anidock.Border))
    }
}

/** 选择器一行:按 kind 渲染图标 + 文字。USE=主操作按钮;shortcut=快捷入口 chip(强调色);其余=普通浏览行。 */
@Composable
private fun PickRow(it: PickItem, focused: Boolean) {
    if (it.kind == PickItem.USE) {
        PickButtonRow(it.label, focused)
        return
    }
    val chip = it.shortcut
    val shape = RoundedCornerShape(8.dp)
    val bg = when {
        focused -> Anidock.Accent.copy(alpha = 0.2f)
        chip -> Anidock.Accent.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val borderColor = when {
        focused -> Anidock.Accent
        chip -> Anidock.Border
        else -> Color.Transparent
    }
    val textColor = when {
        it.disabled -> Anidock.Text3
        focused -> Color.White
        chip -> Anidock.Accent
        else -> Anidock.Text
    }
    val iconColor = when {
        focused -> Color.White
        chip -> Anidock.Accent
        else -> Anidock.Text3
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .border(width = if (focused) 2.dp else if (chip) 1.dp else 0.dp, color = borderColor, shape = shape)
            .background(bg)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(pickIcon(it), null, tint = iconColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(it.label, color = textColor, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 每类选择项的图标(与文字搭配,远看也能分清:子目录=文件夹,预设=书签,U盘卷=硬盘,手动=铅笔)。 */
private fun pickIcon(it: PickItem): ImageVector = when (it.kind) {
    PickItem.USE -> Icons.Default.Check
    PickItem.UP -> Icons.Default.ExpandLess
    PickItem.DIR -> if (it.shortcut) Icons.Default.Usb else Icons.Default.Folder   // U盘卷(shortcut) vs 子目录
    PickItem.IMG -> Icons.Default.Image
    PickItem.SYSDOC, PickItem.SYSDOC_DISABLED -> Icons.Default.Monitor
    PickItem.PRESET -> Icons.Default.Bookmark
    PickItem.MANUAL -> Icons.Default.Edit
    PickItem.CLEAR -> Icons.Default.Delete
    else -> Icons.Default.Folder
}

/** 当前目录路径框:标签 + 路径,比裸文本更清晰(放在选择器顶部)。 */
@Composable
private fun PathBox(path: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Anidock.BgSoft)
            .border(1.dp, Anidock.BorderSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("当前", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
            Text(path, color = Anidock.Text2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SettingRowView(def: SettingRowDef, focused: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Anidock.Accent else Anidock.BorderSoft,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(def.label, color = Anidock.Text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(16.dp))
        when {
            def.toggle != null -> {
                // 开关行:label 之后留空,右侧放 hint(可选) + 真滑块
                Spacer(Modifier.weight(1f))
                if (def.hint.isNotEmpty()) Text(def.hint, color = Anidock.Text3, style = MaterialTheme.typography.bodySmall)
                if (def.hint.isNotEmpty()) Spacer(Modifier.width(12.dp))
                ToggleSwitch(def.toggle == true)
            }
            else -> {
                Text(
                    def.value,
                    color = if (def.value.isEmpty()) Anidock.Text3 else Anidock.Text2,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (def.hint.isNotEmpty()) {
                    Text(def.hint, color = Anidock.Text3, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 分区标题:分组设置项,提供视觉层级(小字 + accent 色,高度固定 30dp 供滚动定位)。 */
@Composable
internal fun SectionHeader(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(top = 10.dp),
    ) {
        Text(
            text,
            color = Anidock.Accent,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        )
    }
}

/** 真滑块开关:开=accent 轨道 + accent 圆钮靠右,关=灰轨道 + 灰圆钮靠左。 */
@Composable
internal fun ToggleSwitch(on: Boolean) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (on) Anidock.Accent.copy(alpha = 0.32f) else Anidock.Border),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) Anidock.Accent else Anidock.Text3),
        )
    }
}
