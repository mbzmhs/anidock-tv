package com.mbzmhs.anidocktv.data

import android.util.Log
import okhttp3.ConnectionPool
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.URL

/**
 * app 层 HTTP(anidock API / 封面拉取)的代理,跟随下载引擎的代理配置(单一配置源:
 * proxy.mode + proxy.url,在引擎设置页编辑)。mode=manual 且 url 可解析时,app 进程出网
 * 一律走该代理,否则直连——这样无需把本 app 包名加进系统级代理软件的管控名单,
 * 封面和 API 也能经代理正常拉取。
 *
 * 与引擎自身的代理用途开关(forHTTP/forPeer/...)互不干扰:引擎管 BT/种子,本类只管 app 进程。
 *
 * 限制:OkHttp 不支持带账号密码的 socks5(socks5://user:pass@...),该地址下 app 层自动
 * 回退直连(仅 log 提示,引擎侧不受影响);环回地址(127.0.0.1/localhost/::1)永远直连
 * (本地引擎 API / 本机 anidock 服务绝不能绕去代理)。
 */
object AppProxy {
    private const val TAG = "appproxy"

    @Volatile var mode: String = "none"
        private set

    @Volatile var url: String = ""
        private set

    /** 挂了本 selector 的 OkHttp 连接池(API/Coil)。keep-alive 空闲连接会被 OkHttp 直接
     *  复用、不再调用 selector 裁决,代理变更时必须踢池,否则流量还走旧代理连接。 */
    private val pools = java.util.concurrent.CopyOnWriteArrayList<ConnectionPool>()

    fun register(pool: ConnectionPool) { pools.add(pool) }

    /** 当前应使用的代理(每次请求实时读取本对象状态);null = 直连。 */
    private fun current(): Proxy? {
        if (mode != "manual") return null
        val u = url.trim()
        if (u.isEmpty()) return null
        return try {
            val uri = URI(u)
            val host = uri.host
            if (host.isNullOrEmpty()) {
                Log.w(TAG, "代理地址缺 host,app 层回退直连: $u")
                return null
            }
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme?.lowercase() in listOf("socks", "socks5") -> 1080
                else -> 80
            }
            when (uri.scheme?.lowercase()) {
                "http", "https" -> Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
                "socks", "socks5" ->
                    if (uri.userInfo != null) {
                        Log.w(TAG, "socks 带账号密码,OkHttp 不支持,app 层回退直连: $u")
                        null
                    } else Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
                else -> {
                    Log.w(TAG, "未知代理协议,app 层回退直连: $u")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "代理地址解析失败,app 层回退直连: $u", e)
            null
        }
    }

    /** 挂到 OkHttp client 的 ProxySelector:环回直连,其余按 current()。
     *  compileSdk 35 的抽象签名是 URI 版,而电视 Android 9(API 28)运行时基类仍是 URL 版
     *  抽象方法——两套都实现:URI 版按新签名 override,URL 版作普通方法,新旧系统都能跑。 */
    fun selector(): ProxySelector = object : ProxySelector() {
        private fun pick(host: String?): List<Proxy> {
            if (host == "127.0.0.1" || host == "localhost" || host == "::1") return listOf(Proxy.NO_PROXY)
            return listOf(current() ?: Proxy.NO_PROXY)
        }

        // API 35+(编译期签名)
        override fun select(uri: java.net.URI): List<Proxy> = pick(uri.host)

        override fun connectFailed(
            uri: java.net.URI,
            address: java.net.SocketAddress,
            e: java.io.IOException,
        ) {
            Log.w(TAG, "代理连接失败 $uri via $address(后续请求仍重试同代理,检查代理地址/端口)", e)
        }

        // API ≤34(电视 Android 9 运行时签名):普通方法,恰好实现旧版基类的抽象方法
        fun select(url: URL): List<Proxy> = pick(url.host)

        fun proxyFailed(url: URL, address: java.net.SocketAddress) {
            Log.w(TAG, "代理连接失败 $url via $address(后续请求仍重试同代理,检查代理地址/端口)")
        }
    }

    /** 从引擎配置同步(mode+url)并落缓存。启动阶段(引擎就绪后)+ 引擎设置页保存后调用。
     *  状态真变了(热切换)→ 踢连接池(keep-alive 旧连接绕过 selector 裁决,不踢则流量还走旧代理)
     *  + 清登录失败锁(否则切 tab 触发的重登会被旧失败记录挡住)。页面刷不刷由用户切 tab/翻页
     *  自行触发——热切换只保证"下一次出网请求走新代理",不强制刷新页面。 */
    suspend fun sync(cfg: EngineService.EngineConfig, dao: SettingDao) {
        val m = cfg.proxy.mode
        val u = cfg.proxy.url.trim()
        val changed = m != mode || u != url
        mode = m
        url = u
        runCatching { dao.put(SettingEntity(SettingKeys.APP_PROXY, "$mode|$url")) }
            .onFailure { Log.e(TAG, "缓存代理配置失败", it) }
        if (changed) {
            // evictAll 会关闭 socket:主线程的 socket 操作(含 close)必抛 NetworkOnMainThreadException,
            // sync 由设置页主线程协程调用,踢池必须挪后台线程
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                for (p in pools) p.evictAll()
            }
            try {
                com.mbzmhs.anidocktv.Services.repo.resetLoginError()
            } catch (_: Exception) {
            }
        }
    }

    /** 启动时从 settings 表缓存恢复(格式 "mode|url");引擎起来后会被 sync 覆盖。 */
    fun restore(cached: String?) {
        if (cached.isNullOrBlank()) return
        val i = cached.indexOf('|')
        if (i < 0) return
        mode = cached.take(i).ifBlank { "none" }
        url = cached.substring(i + 1)
        Log.d(TAG, "启动恢复缓存代理配置: mode=$mode url=$url")
    }
}
