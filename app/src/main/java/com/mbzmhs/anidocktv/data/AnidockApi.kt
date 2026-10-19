package com.mbzmhs.anidocktv.data

import com.mbzmhs.anidocktv.model.Anime
import com.mbzmhs.anidocktv.model.EpisodeView
import com.mbzmhs.anidocktv.model.LoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** anidock 公开 API 客户端(只读 + 访客登录)。token 由外部注入并持久化。 */
class AnidockApi(private var baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile var token: String = ""

    val currentBaseUrl: String get() = baseUrl

    fun setBaseUrl(url: String) { baseUrl = url.trimEnd('/') }

    /** 访客登录,返回 token。失败抛异常。 */
    suspend fun login(password: String): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LoginReq.serializer(), LoginReq(password))
        val req = Request.Builder()
            .url("$baseUrl/api/visitor/login")
            .post(body.toRequestBody())
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("登录失败: HTTP ${resp.code} $text")
            val lr = json.decodeFromString(LoginResponse.serializer(), text)
            token = lr.token
            lr.token
        }
    }

    /** 番剧分页列表(与 web 一致:year/quarter/has_eps 筛选,limit/offset 分页;sort: latest|season;
     *  search: 关键字搜索,服务端匹配标题/别名/标签,非空时拼 q=)。 */
    suspend fun listAnime(year: Int = 0, quarter: Int = 0, hasEps: Boolean = false, search: String = "", limit: Int = 0, offset: Int = 0, sort: String = "season"): List<Anime> =
        withContext(Dispatchers.IO) {
            var url = "$baseUrl/api/anime"
            val params = buildList {
                if (year > 0) add("year=$year")
                if (quarter > 0) add("quarter=$quarter")
                if (hasEps) add("has_eps=1")
                if (search.isNotBlank()) add("q=${android.net.Uri.encode(search)}")
                if (limit > 0) add("limit=$limit")
                if (offset > 0) add("offset=$offset")
                if (sort.isNotBlank() && sort != "season") add("sort=$sort")   // season 是默认值,不传
            }
            if (params.isNotEmpty()) url += "?" + params.joinToString("&")
            get(url) { json.decodeFromString<List<Anime>>(it) }
        }

    /** 按 id 批量拉取番剧摘要(一次请求,替代逐部请求;供追番列表刷新用)。 */
    suspend fun listAnimeByIds(ids: List<Long>): List<Anime> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val url = "$baseUrl/api/anime?ids=${ids.joinToString(",")}"
        get(url) { json.decodeFromString<List<Anime>>(it) }
    }

    /** 分页用总数(同筛选条件下,含 search)。 */
    suspend fun countAnime(year: Int = 0, quarter: Int = 0, hasEps: Boolean = false, search: String = ""): Int = withContext(Dispatchers.IO) {
        var url = "$baseUrl/api/anime/count"
        val params = buildList {
            if (year > 0) add("year=$year")
            if (quarter > 0) add("quarter=$quarter")
            if (hasEps) add("has_eps=1")
            if (search.isNotBlank()) add("q=${android.net.Uri.encode(search)}")
        }
        if (params.isNotEmpty()) url += "?" + params.joinToString("&")
        get(url) {
            val obj = json.parseToJsonElement(it).jsonObject
            obj["total"]?.jsonPrimitive?.content?.toInt() ?: 0
        }
    }

    /** 单部番剧(详情页)。 */
    suspend fun getAnime(id: Long): Anime = withContext(Dispatchers.IO) {
        get("$baseUrl/api/anime/$id") { json.decodeFromString(it) }
    }

    /** 某番剧的每集去重结果。 */
    suspend fun episodes(animeId: Long): List<EpisodeView> = withContext(Dispatchers.IO) {
        get("$baseUrl/api/anime/$animeId/episodes") { json.decodeFromString<List<EpisodeView>>(it) }
    }

    /** 封面/媒体完整 URL(cover 可能是相对路径 media/xxx.jpg)。 */
    fun resolveMedia(path: String): String =
        if (path.isBlank()) "" else if (path.startsWith("http")) path else "$baseUrl/$path"

    private inline fun <T> get(url: String, parse: (String) -> T): T {
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("请求失败: HTTP ${resp.code} $text")
            return parse(text)
        }
    }
}

@Serializable
internal data class LoginReq(@SerialName("password") val password: String)
