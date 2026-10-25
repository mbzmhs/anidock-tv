package com.mbzmhs.anidocktv.data

import android.content.Context
import android.util.Log
import com.mbzmhs.anidocktv.Services
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ZDownloader 引擎宿主:assets 内嵌的单二进制(纯 Go,armv7 静态编译,桌面/headless 同一份代码)
 * 首启解压到 filesDir 后带 -headless 拉起(无 Wails 窗口/托盘/自启),TV 端经 127.0.0.1
 * HTTP API 建任务/查进度/删任务。实例探测:先 GET /api/ping,活着就复用(不重复拉起);
 * 完成后默认不做种(自动做种可在引擎设置页开启)。
 * 进程存活由 EngineWatchdogService 前台服务守护:引擎被系统(电视待机)杀掉时自动拉起。
 */
object EngineService {
    private const val TAG = "AnidockTV"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)   // 轮询(listTasks)等短请求:5s 上限防 TV 高负载拖死
        .build()
    // 建任务(POST /api/tasks、/api/tasks/bt)是重操作:引擎解析种子/建任务/触发 DHT,
    // 高负载下响应可达数十秒。独立长超时 client,否则建任务超时→报错+引擎侧孤儿任务。
    private val createClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var process: Process? = null

    @Serializable
    data class ZdlTask(
        val id: String = "",
        val kind: String = "",
        val filename: String = "",
        val dir: String = "",
        val size: Long = 0,
        val downloaded: Long = 0,
        val status: String = "",
        val error: String = "",
        val speed: Long = 0,
        val arrived: Long = 0,   // BT:已落盘去重字节(进度条口径)
    )

    /** BT 种子内一个文件(详情接口):path 为种子内相对路径(子目录为多段,以 / 连接)。 */
    @Serializable
    data class ZdlFile(
        val path: List<String> = emptyList(),
        val size: Long = 0,
        val done: Long = 0,            // 已落定字节(进度展示)
        val wanted: Boolean = true,    // 是否在当前文件选择内(未选=跳过不下载)
    )

    /** 任务详情(GET /api/tasks/{id}/detail 的子集):合集文件选择对话框用。
     *  files 可空:引擎对尚无文件清单的任务(如磁链解析中/引擎刚重启未装载)会回 files:null。 */
    @Serializable
    data class ZdlDetail(
        val status: String = "",
        val filename: String = "",
        val size: Long = 0,
        val arrived: Long = 0,
        val files: List<ZdlFile>? = null,
    )

    private fun base(): String = "http://127.0.0.1:${Services.enginePort}"

    private fun pingSync(): Boolean = try {
        val resp = pingClient.newCall(Request.Builder().url(base() + "/api/ping").get().build()).execute()
        val ok = resp.isSuccessful
        resp.close()
        ok
    } catch (e: Exception) {
        false
    }

    fun isRunning(): Boolean = pingSync()

    /** 防并发的 ensureRunning(启动看门狗/各 UI 页)同时判定"未运行"而拉起重复进程。 */
    private val startupMutex = Mutex()

    /** 引擎未运行则拉起(解压二进制 + 传 -headless/-port/-dir/-data),轮询 ping 至就绪。 */
    suspend fun ensureRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
        startupMutex.withLock { ensureRunningLocked(context) }
    }

    private suspend fun ensureRunningLocked(context: Context): Boolean {
        var up = pingSync()
        if (!up) {
            val bin = extractBinary(context)
            if (bin != null) {
                val dataDir = File(context.filesDir, "zdownloader-data")
                // 引擎直接下到最终下载目录(用户可指 U 盘):去掉私有工作区+搬文件那步,
                // 不再把成品先堆 /data 再跨分区 copy(/data 仅 4G 且常被塞满)。
                // 任务的实际落盘目录由 createBtTask/createHttpTask 的 dir 参数各自指定。
                val dlDir = File(Services.downloadDir).apply { mkdirs() }
                try {
                    process = ProcessBuilder(
                        bin.absolutePath,
                        "-port", Services.enginePort.toString(),
                        "-dir", dlDir.absolutePath,
                        "-data", dataDir.absolutePath,
                    ).redirectErrorStream(true)
                        // 引擎 stdout/stderr 绝不能留在无人读的继承管道:管道 64KB 写满后,
                        // 引擎的日志调用(MultiWriter 文件+stderr)阻塞,持锁写日志的 goroutine
                        // 会把整个 BT 会话冻死(peer 不再更新/供数,detail 接口挂起)。
                        // 引擎自身已把完整日志落 zdownloader.log,stdout 这份纯重复,直接丢弃。
                        .redirectOutput(File("/dev/null")).start()
                    Log.d(TAG, "engine: 已拉起 ${bin.absolutePath} port=${Services.enginePort} dir=${dlDir.absolutePath}")
                    for (i in 0 until 40) {
                        if (pingSync()) { up = true; break }
                        delay(500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "engine: 启动进程失败", e)
                }
            } else {
                Log.e(TAG, "engine: 解压引擎二进制失败")
            }
            if (!up) Log.e(TAG, "engine: 启动超时,未就绪")
        }
        // anidock 强制默认设置(用户不可改):无论引擎是已运行还是刚拉起,都纠正回默认
        if (up) applyAnidockDefaults()
        return up
    }

    /**
     * anidock 强制的引擎默认值(用户不可改,每次引擎起来都纠正):
     *  - autoOpenTorrent=false:种子下载由 anidock 手动建 BT(读 .torrent→AddBT),
     *    若引擎自动建 BT 会跟 anidock 撞车、互相删 .torrent/任务。
     *  - skipTLSVerify=true:信任代理的 MITM 证书。电视系统 DNS 坏(直连解析不了域名),
     *    种子只能走代理,而代理做 TLS 中间人换自签证书,不信任就拉不动 .torrent。
     *    种子只是元数据(不做 BT 传输),信任其 MITM 证书无风险。
     */
    suspend fun applyAnidockDefaults() {
        runCatching {
            saveEngineConfig(buildJsonObject {
                put("autoOpenTorrent", kotlinx.serialization.json.JsonPrimitive(false))
                put("skipTLSVerify", kotlinx.serialization.json.JsonPrimitive(true))
            })
        }.onFailure { Log.w(TAG, "engine: 应用默认设置失败", it) }
    }

    fun stop() {
        process?.let { runCatching { it.destroy() } }
        process = null
    }

    private fun extractBinary(context: Context): File? {
        val bin = File(context.filesDir, "zdownloader/zdownloader")
        // 已部署且大小与 asset 一致才复用;大小不同=引擎升级(换了二进制),重新解压覆盖
        val assetSize = runCatching { context.assets.openFd("zdownloader/zdownloader").use { it.length } }.getOrDefault(0L)
        if (bin.exists() && bin.length() > 1_000_000L && bin.length() == assetSize) {
            bin.setExecutable(true, false)
            return bin
        }
        return runCatching<File> {
            bin.parentFile?.mkdirs()
            context.assets.open("zdownloader/zdownloader").use { input ->
                java.io.FileOutputStream(bin).use { output -> input.copyTo(output) }
            }
            bin.setExecutable(true, false)
            bin
        }.onFailure { Log.e(TAG, "engine: 解压失败", it) }.getOrNull()
    }

    /** 创建 BT 任务(磁链或 base64 种子);dir 为保存目录。失败返回 null。 */
    suspend fun createBtTask(magnet: String?, torrentB64: String?, dir: String): ZdlTask? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
            if (magnet != null) put("magnet", kotlinx.serialization.json.JsonPrimitive(magnet))
            if (torrentB64 != null) put("torrentB64", kotlinx.serialization.json.JsonPrimitive(torrentB64))
            put("dir", kotlinx.serialization.json.JsonPrimitive(dir))
            // choose=true:多文件种子(合集)挂 choosing 等选文件,TV 侧弹文件列表对话框让用户
            // 勾选(POST /files 落定后转 waiting 开始下载);单文件种子引擎自动放行,不受影响。
            put("choose", kotlinx.serialization.json.JsonPrimitive(true))
        }).toRequestBody()
        try {
            val resp = createClient.newCall(
                Request.Builder().url(base() + "/api/tasks/bt").post(body).build()
            ).execute()
            val text = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "engine: 建任务失败 HTTP ${resp.code} $text")
                return@withContext null
            }
            val parsed = json.parseToJsonElement(text).jsonObject
            val tasks = (parsed["tasks"] as? JsonArray)?.mapNotNull { el ->
                runCatching { json.decodeFromString<ZdlTask>(el.toString()) }.getOrNull()
            } ?: emptyList()
            if (tasks.isEmpty()) {
                Log.e(TAG, "engine: 建任务无结果: $text")
                return@withContext null
            }
            Log.d(TAG, "engine: 建任务成功 id=${tasks.first().id} ${tasks.first().filename}")
            tasks.first()
        } catch (e: Exception) {
            Log.e(TAG, "engine: 建任务异常", e)
            null
        }
    }

    /** 建 HTTP 下载任务(下载种子等,POST /api/tasks)。多线程+可走代理(proxy 空串=直连)。失败返回 null。 */
    suspend fun createHttpTask(url: String, dir: String, proxy: String, filename: String): ZdlTask? =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive(url))
                put("dir", kotlinx.serialization.json.JsonPrimitive(dir))
                if (filename.isNotBlank()) put("filename", kotlinx.serialization.json.JsonPrimitive(filename))
                put("proxy", kotlinx.serialization.json.JsonPrimitive(proxy))
            }).toRequestBody()
            try {
                val resp = createClient.newCall(
                    Request.Builder().url(base() + "/api/tasks").post(body).build()
                ).execute()
                val text = resp.body?.string() ?: ""
                resp.close()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "engine: 建 HTTP 任务失败 HTTP ${resp.code} $text")
                    return@withContext null
                }
                val parsed = json.parseToJsonElement(text).jsonObject
                val tasks = (parsed["tasks"] as? JsonArray)?.mapNotNull { el ->
                    runCatching { json.decodeFromString<ZdlTask>(el.toString()) }.getOrNull()
                } ?: emptyList()
                if (tasks.isEmpty()) {
                    Log.e(TAG, "engine: 建 HTTP 任务无结果: $text")
                    return@withContext null
                }
                Log.d(TAG, "engine: 建 HTTP 任务成功 id=${tasks.first().id} ${tasks.first().filename}")
                tasks.first()
            } catch (e: Exception) {
                Log.e(TAG, "engine: 建 HTTP 任务异常", e)
                null
            }
        }

    @Serializable
    data class EngineBtConfig(
        val maxPeers: Int = 60,          // 单任务最大 peer 连接数
        val upSpeed: Long = 0,           // 全局上传限速 B/s,0 不限
        val dht: Boolean = true,
        val pex: Boolean = true,
        val autoSeed: Boolean = false,   // 完成后自动做种(headless 默认关)
        val seedRatio: Double = 0.0,     // 目标分享率,0 = 不按比率停
        val seedMinutes: Int = 0,        // 最短做种时长(分),0 = 不按时长停
        val autoAddTrackers: Boolean = true,
        val autoBanPoison: Boolean = false,
        val poisonRate: Int = 80,        // 白名单 piece 有效率阈值(%),50~100
        val poisonStrikes: Int = 3,      // 坏块宽容次数,1~10
        val publicTrackers: List<String>? = null,  // 公共 tracker 列表
        val spoofUA: Boolean = true,      // 伪装 BT 客户端 ID
        val spoofName: String = "",       // 伪装名称(如 "BitComet 1.99")
    )

    @Serializable
    data class EngineProxyConfig(
        val mode: String = "none",       // none | manual | env(跟随系统环境变量)
        val url: String = "",            // 代理地址:http(s)://host:port 或 socks5://user:pass@host:port
        val forHTTP: Boolean = true,     // HTTP/HTTPS 下载任务
        val forTracker: Boolean = true,  // BT tracker 连接
        val forPeer: Boolean = true,     // BT peer 连接
        val forWebSeed: Boolean = true,  // BT WebSeed(长效种子)拉取
        val dohDNS: Boolean = true,      // DNS 解析走代理(DoH,防本地污染)
    )

    @Serializable
    data class EngineConfig(
        val maxActiveTasks: Int = 3,     // 同时进行的任务数,1~10
        val maxConnections: Int = 8,     // 默认每任务线程数,1~64
        val globalLimit: Long = 0,       // 全局下载限速 B/s,0 不限
        val bt: EngineBtConfig = EngineBtConfig(),
        val proxy: EngineProxyConfig = EngineProxyConfig(),
    )

    /** 引擎当前配置(GET /api/config)。失败返回 null。 */
    suspend fun getEngineConfig(): EngineConfig? = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url(base() + "/api/config").get().build()).execute()
            val text = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "engine: 读配置 HTTP ${resp.code}")
                null
            } else json.decodeFromString<EngineConfig>(text)
        } catch (e: Exception) {
            Log.e(TAG, "engine: 读配置失败(引擎可能未运行)", e)
            null
        }
    }

    /** "跟随系统"代理实时检测结果(GET /api/sysproxy)。失败返回 null。 */
    @Serializable
    data class SystemProxyStatus(
        val source: String = "",   // ""=未检测到; "env"=环境变量; 其余=平台系统代理
        val envVar: String = "",   // 环境变量名(仅 env 来源)
        val addr: String = "",     // 代理地址(如 socks5://host:port)
        val url: String = "",      // 规范化 URL
    )

    suspend fun getSysProxy(): SystemProxyStatus? = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url(base() + "/api/sysproxy").get().build()).execute()
            val text = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "engine: 读系统代理 HTTP ${resp.code}")
                null
            } else json.decodeFromString<SystemProxyStatus>(text)
        } catch (e: Exception) {
            Log.e(TAG, "engine: 读系统代理失败(引擎可能未运行)", e)
            null
        }
    }

    /** 部分更新引擎配置:PUT /api/config 与现值合并,未含字段保持。速度/并发/BT 开关均热生效。 */
    suspend fun saveEngineConfig(patch: JsonObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(JsonObject.serializer(), patch).toRequestBody()
            val resp = client.newCall(
                Request.Builder().url(base() + "/api/config").put(body).build()
            ).execute()
            val text = resp.body?.string() ?: ""
            val ok = resp.isSuccessful
            if (!ok) Log.e(TAG, "engine: 保存配置 HTTP ${resp.code} ${text.take(200)}")
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "engine: 保存配置失败", e)
            false
        }
    }

    @Serializable
    data class BanEntry(
        val ip: String = "",
        val time: String = "",      // RFC3339
        val reason: String = "",
        val client: String = "",
        val count: Int = 0,         // 累计封禁次数
    )

    @Serializable
    private data class BanListResp(val bans: List<BanEntry> = emptyList())

    /** 保存完整 BT 配置:数据类整体序列化,不再手工逐字段枚举(引擎加字段时只需更新 EngineBtConfig 一处)。 */
    suspend fun saveBtConfig(bt: EngineBtConfig): Boolean =
        saveEngineConfig(buildJsonObject { put("bt", json.encodeToJsonElement(EngineBtConfig.serializer(), bt).jsonObject) })

    /** 封禁名单(GET /api/bt/bans)。失败返回 null。 */
    suspend fun getBans(): List<BanEntry>? = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url(base() + "/api/bt/bans").get().build()).execute()
            val text = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "engine: 读封禁名单 HTTP ${resp.code}")
                null
            } else json.decodeFromString<BanListResp>(text).bans
        } catch (e: Exception) {
            Log.e(TAG, "engine: 读封禁名单失败(引擎可能未运行)", e)
            null
        }
    }

    /** 解除单个 IP 封禁(DELETE /api/bt/bans/{ip})。 */
    suspend fun unban(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = base() + "/api/bt/bans/" + java.net.URLEncoder.encode(ip, "UTF-8")
            val resp = client.newCall(Request.Builder().url(url).delete().build()).execute()
            val ok = resp.isSuccessful
            if (!ok) Log.e(TAG, "engine: 解封失败 HTTP ${resp.code} ip=$ip")
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "engine: 解封异常 ip=$ip", e)
            false
        }
    }

    /** 清空封禁名单(DELETE /api/bt/bans)。 */
    suspend fun clearBans(): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url(base() + "/api/bt/bans").delete().build()).execute()
            val ok = resp.isSuccessful
            if (!ok) Log.e(TAG, "engine: 清空封禁失败 HTTP ${resp.code}")
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "engine: 清空封禁异常", e)
            false
        }
    }

    /** 全部任务快照(进度回写用)。失败返回空并记日志。 */
    suspend fun listTasks(): List<ZdlTask> = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(Request.Builder().url(base() + "/api/tasks").get().build()).execute()
            val text = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "engine: listTasks HTTP ${resp.code}")
                return@withContext emptyList()
            }
            json.decodeFromString<List<ZdlTask>>(text)
        } catch (e: Exception) {
            Log.e(TAG, "engine: listTasks 失败(引擎可能未运行)", e)
            emptyList()
        }
    }

    /** 删除任务;deleteFile=true 连同已下载文件一起删(引擎 ?file=1)。 */
    suspend fun deleteTask(taskId: String, deleteFile: Boolean) {
        runCatching {
            withContext(Dispatchers.IO) {
                val url = base() + "/api/tasks/" + taskId + (if (deleteFile) "?file=1" else "")
                val resp = client.newCall(Request.Builder().url(url).delete().build()).execute()
                resp.close()
            }
        }.onFailure { Log.e(TAG, "engine: 删任务失败 $taskId", it) }
    }

    /** 取消任务(停止并清理临时文件)。 */
    suspend fun cancelTask(taskId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val resp = client.newCall(
                    Request.Builder().url(base() + "/api/tasks/" + taskId + "/cancel").post("".toRequestBody()).build()
                ).execute()
                resp.close()
            }
        }.onFailure { Log.e(TAG, "engine: 取消任务失败 $taskId", it) }
    }

    /** pause / resume / cancel 统一入口:POST /api/tasks/{id}/{action}。成功返回 true。 */
    suspend fun actionTask(taskId: String, action: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(
                Request.Builder().url(base() + "/api/tasks/" + taskId + "/" + action).post("".toRequestBody()).build()
            ).execute()
            val ok = resp.isSuccessful
            if (!ok) Log.e(TAG, "engine: $action 失败 $taskId HTTP ${resp.code}")
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "engine: $action 异常 $taskId", e)
            false
        }
    }

    /**
     * 边下边播流地址(环回,供内置播放器直接播)。file 省略=引擎自动选种子内最大的媒体文件;
     * 指定 file(种子内相对路径,子目录以 / 连接)= 合集里播指定文件。
     * v 为会话代次,每次进入换值即开新流会话,旧代次的请求被引擎以 409 拒绝(防切集后旧播放器拽回旧会话)。
     */
    fun streamUrl(taskId: String, generation: Long, file: String? = null): String =
        base() + "/api/tasks/" + taskId + "/stream?v=" + generation +
            (if (!file.isNullOrEmpty()) "&file=" + java.net.URLEncoder.encode(file, "UTF-8") else "")

    /** 任务详情:文件列表(种子内相对路径/大小/进度/是否在选)+ 状态。合集文件对话框用;失败返回 null。 */
    suspend fun taskDetail(taskId: String): ZdlDetail? = withContext(Dispatchers.IO) {
        try {
            val resp = client.newCall(
                Request.Builder().url(base() + "/api/tasks/" + taskId + "/detail").get().build()
            ).execute()
            val text = resp.body?.string() ?: ""
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "engine: taskDetail HTTP ${resp.code}")
                null
            } else json.decodeFromString<ZdlDetail>(text)
                // 归一化:files:null(尚无文件清单)对调用方按空表处理,避免逐处判空
                ?.let { if (it.files == null) it.copy(files = emptyList()) else it }
        } catch (e: Exception) {
            Log.e(TAG, "engine: taskDetail 失败(引擎可能未运行)", e)
            null
        }
    }

    /**
     * 落定/修改 BT 文件选择:choosing 确认后立即排队下载;运行中/做种/已完成改选由引擎
     * 自动按新选择重启会话(缩选已覆盖时只改账不打断)。至少一个文件,失败返回 false。
     */
    suspend fun chooseFiles(taskId: String, files: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put(
                    "files",
                    kotlinx.serialization.json.JsonArray(files.map { kotlinx.serialization.json.JsonPrimitive(it) })
                )
            }).toRequestBody()
            val resp = createClient.newCall(
                Request.Builder().url(base() + "/api/tasks/" + taskId + "/files").post(body).build()
            ).execute()
            val text = resp.body?.string() ?: ""
            val ok = resp.isSuccessful
            if (!ok) Log.e(TAG, "engine: chooseFiles HTTP ${resp.code} $text")
            resp.close()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "engine: chooseFiles 异常", e)
            false
        }
    }

    /** 边下边播预热:把同任务指定文件(下一集)的头/尾元数据 piece 并入最高优先级,不切换播放会话。 */
    suspend fun warmStream(taskId: String, file: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val body = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("file", kotlinx.serialization.json.JsonPrimitive(file))
                }).toRequestBody()
                val resp = client.newCall(
                    Request.Builder().url(base() + "/api/tasks/" + taskId + "/stream/warm").post(body).build()
                ).execute()
                resp.close()
            }
        }.onFailure { Log.w(TAG, "engine: 预热失败 $taskId", it) }
    }

    /** 停止边下边播,还原纯下载优先级(播放器退出时调用;不调引擎也会在 5 分钟空闲后自动回收)。 */
    suspend fun stopStream(taskId: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val resp = client.newCall(
                    Request.Builder().url(base() + "/api/tasks/" + taskId + "/stream/stop").post("".toRequestBody()).build()
                ).execute()
                resp.close()
            }
        }.onFailure { Log.w(TAG, "engine: 停流失败 $taskId", it) }
    }
}
