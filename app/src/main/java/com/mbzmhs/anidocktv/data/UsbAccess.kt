package com.mbzmhs.anidocktv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

/**
 * 下载根目录可用性判定 + U 盘支持(面向普通用户电视,零特权):
 * - 统一落盘:引擎直接下载进设置里的"下载根目录"(内部存储或 U 盘卷)。
 * - 两条写入通道,对引擎透明:
 *   1. 直写:内部存储,或 app 可直接写的 U 盘卷(视电视实现)。
 *   2. SAF(存储访问框架):U 盘对普通 app 只读的电视,用户经系统文件选择器
 *      对 U 盘上某目录做一次授权(takePersistableUriPermission 持久化,重启自动恢复),
 *      引擎写出的文件由系统 externalstorage provider 代写,直接落在授权目录。
 * - 无系统选择器的设备(如 Sony BRAVIA 的 documentsui 是残废桩):U 盘目录无法授权,
 *   下载根目录只能选内部存储,界面明确提示。
 * 本对象提供的探测/授权能力同时是设置页"目录能否当下载根目录"的判断依据。
 */
object UsbAccess {
    const val AUTHORITY = "com.android.externalstorage.documents"
    private const val TAG = "usbplace"

    /** 已挂载可移动卷(U 盘)。 */
    fun removableVolumes(context: Context): List<Any> = runCatching {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
    }.getOrDefault(emptyList())

    private fun volPath(v: Any): String = v.javaClass.getMethod("getPath").invoke(v) as String

    /** 目录对应的 U 盘树授权 URI(内部存储目录不属于 U 盘,返回 null)。 */
    fun treeUriForPath(context: Context, path: String): Uri? {
        val vol = removableVolumes(context).firstOrNull { p -> path == p || path.startsWith("$p/") } ?: return null
        val p = volPath(vol)
        val rootId = p.trimEnd('/').substringAfterLast('/')
        val rel = path.removePrefix(p).trimStart('/')
        val docId = if (rel.isEmpty()) "$rootId:" else "$rootId:$rel/"
        return Uri.parse("content://$AUTHORITY/tree/$docId")
    }

    /** 授权树 URI 对应的文件系统路径(U 盘根或子目录)。 */
    fun pathOfTree(context: Context, treeUri: Uri): String? {
        val docId = treeUri.path?.removePrefix("/tree/") ?: return null
        val rootId = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "").trimEnd('/')
        val vol = removableVolumes(context).firstOrNull {
            volPath(it).trimEnd('/').substringAfterLast('/') == rootId
        } ?: return null
        val base = volPath(vol)
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    /** 当前持久授权的 U 盘树列表(该 API 返回的本就是持久授权;系统重启后自动恢复)。 */
    fun authorizedTrees(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.uri.authority == AUTHORITY && it.uri.path?.startsWith("/tree/") == true }
            .map { it.uri }

    /** 本机是否有系统文件选择器(U 盘授权的前提)。 */
    fun pickerAvailable(context: Context): Boolean =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0).isNotEmpty()

    /** 系统选择器意图;initialTreeUri 非空时选择器直接定位到该处(用户只需确认)。 */
    fun pickerIntent(initialTreeUri: Uri? = null): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addCategory(Intent.CATEGORY_DEFAULT)
        // stub 缺 EXTRA_INITIAL_URI 常量,用字面量
        if (initialTreeUri != null) i.putExtra("android.provider.extra.INITIAL_URI", initialTreeUri)
        return i
    }

    /** 选择器返回后:一次性授权转持久授权。 */
    fun takePersist(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        Log.d("AnidockTV", "$TAG: 已持久授权 $uri")
    }

    /** 授权目录展示名(路径末段)。 */
    fun treeLabel(context: Context, tree: Uri?): String {
        if (tree == null) return "未授权"
        val p = runCatching { pathOfTree(context, tree) }.getOrNull() ?: return "未知"
        return p.trimEnd('/').substringAfterLast('/').ifEmpty { "U盘根" }
    }

    /** 目录是否可作下载根目录:直写可达,或被某个已授权 U 盘树覆盖。 */
    fun placeable(context: Context, dir: File): Boolean {
        if (probeDirect(dir)) return true
        val d = dir.absolutePath
        return authorizedTrees(context).any { t ->
            runCatching {
                val p = pathOfTree(context, t) ?: return@runCatching false
                d == p || d.startsWith("$p/")
            }.getOrDefault(false)
        }
    }

    /** 直写探测(引擎子进程同域,结果一致)。 */
    fun probeDirect(dir: File): Boolean = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return false
        val t = File(dir, ".anidock_wtest")
        if (!t.createNewFile() && !t.exists()) return false
        t.delete()
        true
    }.getOrDefault(false)

}
