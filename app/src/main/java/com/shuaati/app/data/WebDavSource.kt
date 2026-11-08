// 数据层：题库源的 WebDAV 访问（读索引 / 下题库 / 下图片 / 测试连接 / 上传）。
//
// 11 起这一层不再认「自定义地址字符串」，而是认一个 SourceConfig（data/SourceStore.kt）。
// 三条硬规矩（任务书）：
//   1. 只用系统 HttpURLConnection，不引任何第三方网络库；
//   2. 所有网络调用都在 Dispatchers.IO 上，连接超时 8 秒、读取超时 8 秒（v1.3 按任务书缩短）；
//   3. 出错一律抛 WebDavException（中文原因），界面直接显示，绝不崩。
//
// 凭据红线（硬性要求 3）：
//   WEBDAV_PASS 只出现在本文件里（内置默认源的默认值），日志里只打 URL 与 HTTP 状态码，
//   而 URL 里不含账号密码（账号密码在 Authorization 头里），所以日志天然是安全的。
package com.shuaati.app.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ===== 内置默认源 =====
// 地址 / 账号 / 应用密码从本机 local.properties 读，经 BuildConfig 注入，源码里不含凭据。
// HAS_BUILTIN_SOURCE=false 时这四个值为空，不创建默认源。
internal val HAS_BUILTIN_SOURCE: Boolean = com.shuaati.app.BuildConfig.HAS_BUILTIN_SOURCE
internal val WEBDAV_BASE_URL: String = com.shuaati.app.BuildConfig.DEFAULT_SOURCE_BASE_URL
internal val WEBDAV_USER: String = com.shuaati.app.BuildConfig.DEFAULT_SOURCE_USER

// 应用密码（不是登录密码），只存在于本机 local.properties 里。
internal val WEBDAV_PASS: String = com.shuaati.app.BuildConfig.DEFAULT_SOURCE_PASS

/** 默认源的相对目录 */
internal val WEBDAV_PATH: String = com.shuaati.app.BuildConfig.DEFAULT_SOURCE_PATH

/**
 * 默认源的索引路径。实际地址由 baseUrl + path + papers.json 拼出，
 * 这条常量保留下来只为对齐旧文档/旧报告里的写法。
 */
internal const val WEBDAV_INDEX_PATH = "/shuaati/papers.json"

/** 连接超时（毫秒）：v1.3 起按任务书改成 8 秒 */
private const val CONNECT_TIMEOUT_MS = 8_000

/** 读取超时（毫秒）：v1.3 起按任务书改成 8 秒 */
private const val READ_TIMEOUT_MS = 8_000

/** 请求头里报的客户端名（任务书要求：所有请求都带 shuaati-app/1.3） */
private const val USER_AGENT = "shuaati-app/1.3"

/**
 * 网络相关的错误，message 已经是可以直接显示给使用者看的中文。
 * 界面捕获它并原样显示即可，不要自己再拼一遍。
 */
class WebDavException(message: String) : Exception(message)

/** 索引里的一套可下载试卷（papers.json 的 papers[] 里的一条） */
data class WebDavEntry(
    val id: String,
    val name: String,
    val file: String,
    val total: Int,
    /** 云端版本号：11 新增，启动更新检查靠它比大小；老索引没有这个字段时按 0 处理 */
    val version: Int = 0,
)

/**
 * 索引内容。
 * @param skipped 索引里字段不全、被忽略的条数（>0 时界面提示一句，不影响其它条）
 * @param fileExists papers.json 是否真的存在（404 时为 false，用来区分「空源」和「地址写错」）
 */
data class WebDavIndex(
    val entries: List<WebDavEntry>,
    val skipped: Int,
    val sourceId: String,
    val sourceName: String,
    val fileExists: Boolean,
)

/**
 * 图片下载结果。
 * @param total 这套卷里需要下载的图片张数
 * @param failed 下载失败张数（失败不阻塞导入，只提示）
 * @param failedNames 失败的文件名（最多列 5 个，给界面提示用）
 */
data class ImageDownloadResult(
    val total: Int,
    val failed: Int,
    val failedNames: List<String>,
)

/** WebDAV 题库源：按 SourceConfig 读索引、下题库、下图片、上传、测试连接 */
object WebDavSource {

    private const val TAG = "Shuaati"

    /** 源根目录（baseUrl + path），一定以 / 结尾 */
    fun dirUrl(source: SourceConfig): URL = makeUrl(source.dirText(), "服务器地址")

    /** 源根目录的 papers.json */
    fun indexUrl(source: SourceConfig): URL = makeUrl(source.dirText() + "papers.json", "索引地址")

    /**
     * 版本文件地址：`<该源 baseUrl><该源 path>version.json`。
     * **按源算出来，不写死**——默认源和老师自建的源走的是完全相同的一段代码。
     */
    fun versionUrl(source: SourceConfig): URL = makeUrl(source.dirText() + "version.json", "版本文件地址")

    /** 源根目录下的某个文件（上传试卷时用 `<paperId>.json`） */
    fun paperUrl(source: SourceConfig, fileName: String): URL = makeUrl(source.dirText() + fileName, "试卷地址")

    /** 源根目录 images/ 下的某张图 */
    fun imageUrl(source: SourceConfig, fileName: String): URL =
        makeUrl(source.dirText() + "images/" + fileName, "图片地址")

    private fun makeUrl(text: String, what: String): URL = try {
        URL(text)
    } catch (e: Exception) {
        // 错误提示里只报「格式不对」，不把可能带账号的原文打出去
        throw WebDavException("$what 格式不对，请检查服务器地址与路径（是否以 http:// 或 https:// 开头）")
    }

    /**
     * 读索引（papers.json）。
     * 404 不算异常：返回 fileExists=false 的空索引，让「新建源」能提示「该源还没有试卷」。
     */
    suspend fun loadIndex(source: SourceConfig): WebDavIndex = withContext(Dispatchers.IO) {
        val what = "读取题库源索引（${source.name}）"
        val text = getTextInternal(indexUrl(source), source, what, allow404 = true)
        if (text == null) {
            Log.i(TAG, "索引不存在（HTTP 404）：源=${source.name}")
            return@withContext WebDavIndex(
                entries = emptyList(),
                skipped = 0,
                sourceId = source.id,
                sourceName = source.name,
                fileExists = false,
            )
        }
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw WebDavException("索引文件格式不对：不是合法的 JSON")
        }
        val array = root.optJSONArray("papers")
            ?: throw WebDavException("索引文件格式不对：顶层缺少 papers 数组")

        val entries = ArrayList<WebDavEntry>(array.length())
        var skipped = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skipped++
                continue
            }
            val file = obj.optString("file", "").trim()
            if (file.isEmpty()) {
                skipped++
                continue
            }
            val id = obj.optString("id", "").trim().ifBlank { "entry-$i" }
            val name = obj.optString("name", "").trim().ifBlank { file }
            entries.add(
                WebDavEntry(
                    id = id,
                    name = name,
                    file = file,
                    total = obj.optInt("total", 0),
                    version = obj.optInt("version", 0),
                )
            )
        }
        Log.i(
            TAG,
            "题库源索引：源=${source.name}，可下载 ${entries.size} 套，忽略 $skipped 条",
        )
        WebDavIndex(
            entries = entries,
            skipped = skipped,
            sourceId = source.id,
            sourceName = source.name,
            fileExists = true,
        )
    }

    /**
     * 下载一套试卷的题库 JSON 文本（还没校验，校验由 PaperImporter 做）。
     */
    suspend fun fetchQuestions(source: SourceConfig, entry: WebDavEntry): String =
        withContext(Dispatchers.IO) {
            getTextInternal(resolveFile(source, entry.file), source, "下载「${entry.name}」")
                ?: throw WebDavException("下载「${entry.name}」失败：文件不存在（HTTP 404）")
        }

    /**
     * 把一套试卷里用到的图片下载到该试卷的 images 目录。
     *
     * 图片下载失败**不阻塞导入**：题目本身已经能用了，只是图显示不出来。
     * 失败张数会回给界面提示。
     */
    suspend fun downloadImages(
        source: SourceConfig,
        questionsText: String,
        destDir: File,
    ): ImageDownloadResult = withContext(Dispatchers.IO) {
        val names = collectImageNames(questionsText)
        if (names.isEmpty()) return@withContext ImageDownloadResult(0, 0, emptyList())

        destDir.mkdirs()
        val failed = ArrayList<String>()
        for (name in names) {
            val url = try {
                imageUrl(source, name)
            } catch (e: Exception) {
                failed.add(name)
                continue
            }
            try {
                val bytes = getBytesInternal(url, source, "下载图片 $name")
                    ?: throw WebDavException("下载图片 $name 失败：文件不存在（HTTP 404）")
                File(destDir, name).writeBytes(bytes)
            } catch (e: WebDavException) {
                // 只记文件名与中文原因，不记任何凭据
                Log.w(TAG, "图片下载失败：$name（${e.message}）")
                failed.add(name)
            }
        }
        Log.i(
            TAG,
            "图片下载：源=${source.name}，需要 ${names.size} 张，成功 ${names.size - failed.size} 张，失败 ${failed.size} 张",
        )
        ImageDownloadResult(
            total = names.size,
            failed = failed.size,
            failedNames = failed.take(5),
        )
    }

    /**
     * 测试连接：GET 一次索引。
     * @return 成功时的中文说明（带该源试卷数）；失败抛 WebDavException（401 / 404 / 超时都有中文原因）
     */
    suspend fun testConnection(source: SourceConfig): String = withContext(Dispatchers.IO) {
        val index = loadIndex(source)
        if (!index.fileExists) {
            throw WebDavException(
                "连接成功，但该源根目录下没有 papers.json（HTTP 404）：" +
                    "地址和账号是对的，先上传一套试卷或把现有 papers.json 放进去就能用",
            )
        }
        if (index.entries.isEmpty()) {
            "连接成功：索引可读，但里面还没有试卷（0 套）"
        } else {
            "连接成功：该源有 ${index.entries.size} 套试卷"
        }
    }

    /**
     * 读一个文本文件；404 返回 null（上传前读 papers.json 用：没有就当成空索引）。
     * 其它状态码照常抛 WebDavException。
     */
    suspend fun getTextOrNull(url: URL, source: SourceConfig, what: String): String? =
        withContext(Dispatchers.IO) { getTextInternal(url, source, what, allow404 = true) }

    /**
     * 读公网版本文件 `<源根目录>version.json`（v1.3 新增）。
     *
     * 这是「更新检测」的第一级读取：返回 404 时给 null，由调用方退到第二级 papers.json；
     * 其它网络错误照常抛 WebDavException（调用方只记日志、静默跳过，不弹窗）。
     */
    suspend fun fetchVersionText(source: SourceConfig): String? =
        withContext(Dispatchers.IO) {
            getTextInternal(
                url = versionUrl(source),
                source = source,
                what = "读取版本信息",
                allow404 = true,
            )
        }

    /** 上传文本（题库 JSON、papers.json 都用它）。
     * 只有 2xx 算成功，其余状态码转成中文原因。
     */
    suspend fun putText(source: SourceConfig, url: URL, text: String, what: String) =
        putBytes(source, url, text.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", what)

    /** 上传二进制（图片） */
    suspend fun putBytes(
        source: SourceConfig,
        url: URL,
        bytes: ByteArray,
        contentType: String,
        what: String,
    ): Unit = withContext(Dispatchers.IO) {
        val conn = openConnection(source, url, "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            Log.i(TAG, "WebDAV PUT ${safeUrl(url)} -> HTTP $code")
            if (code in 200..299) return@withContext
            // 把错误响应的前 200 字节读掉，让连接能正常关闭
            runCatching { conn.errorStream?.use { it.readBytes() } }
            throw WebDavException(putStatusMessage(code, what))
        } catch (e: WebDavException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw WebDavException("$what 超时：连接 8 秒、读取 8 秒都没等到响应，请检查网络后重试")
        } catch (e: UnknownHostException) {
            throw WebDavException("$what 失败：连不上服务器（域名解析失败），请检查手机网络")
        } catch (e: ConnectException) {
            throw WebDavException("$what 失败：连不上服务器，请检查手机网络或地址是否写对")
        } catch (e: SSLException) {
            throw WebDavException("$what 失败：安全连接建立失败（证书有问题）")
        } catch (e: IOException) {
            throw WebDavException("$what 失败：网络读写出错（${e.message ?: "未知错误"}）")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 尽力创建源根目录与 images 目录（MKCOL）。
     * 服务器不允许、目录已存在（405/301/409）都**不报错**——真正能不能写，
     * 由随后的 PUT 给出准确结果；这里只是让「全新的空目录」也能上传成功。
     */
    suspend fun ensureDirectories(source: SourceConfig) = withContext(Dispatchers.IO) {
        mkcol(source, dirUrl(source))
        mkcol(source, makeUrl(source.dirText() + "images/", "图片目录地址"))
    }

    /** 读一个文本文件；allow404 = true 时 404 返回 null，其它错误照常抛 */
    private fun getTextInternal(
        url: URL,
        source: SourceConfig,
        what: String,
        allow404: Boolean = false,
    ): String? = getBytesInternal(url, source, what, allow404)
        ?.toString(Charsets.UTF_8)
        /** v1.3：坚果云实测 papers.json 是 UTF-8 无 BOM，但别的 WebDAV 可能带 BOM。
         *  带 BOM 的文本用 JSONObject 解析会直接失败，所以这里统一兜底去掉。 */
        ?.stripBom()

    /** 去掉文本开头的 UTF-8 BOM（\uFEFF）；没有就原样返回 */
    private fun String.stripBom(): String =
        if (isNotEmpty() && this[0] == '\uFEFF') substring(1) else this

    /** 一次 GET 请求的公共实现：状态码 → 中文错误；404 可选放行 */
    private fun getBytesInternal(
        url: URL,
        source: SourceConfig,
        what: String,
        allow404: Boolean = false,
    ): ByteArray? {
        val conn = openConnection(source, url, "GET")
        try {
            val code = conn.responseCode
            Log.i(TAG, "WebDAV GET ${safeUrl(url)} -> HTTP $code")
            if (code == HttpURLConnection.HTTP_OK) {
                return conn.inputStream.use { it.readBytes() }
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND && allow404) {
                runCatching { conn.errorStream?.close() }
                return null
            }
            throw WebDavException(statusMessage(code, what))
        } catch (e: WebDavException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw WebDavException("$what 超时：连接 8 秒、读取 8 秒都没等到响应，请检查网络后重试")
        } catch (e: UnknownHostException) {
            throw WebDavException("$what 失败：连不上服务器（域名解析失败），请检查手机网络")
        } catch (e: ConnectException) {
            throw WebDavException("$what 失败：连不上服务器，请检查手机网络或地址是否写对")
        } catch (e: SSLException) {
            throw WebDavException("$what 失败：安全连接建立失败（证书有问题）")
        } catch (e: IOException) {
            throw WebDavException("$what 失败：网络读写出错（${e.message ?: "未知错误"}）")
        } finally {
            conn.disconnect()
        }
    }

    /** 从题库 JSON 里收集所有图片文件名（去重、只留文件名） */
    private fun collectImageNames(questionsText: String): List<String> {
        val names = LinkedHashSet<String>()
        val root = try {
            JSONObject(questionsText)
        } catch (e: Exception) {
            return emptyList()
        }
        val array = root.optJSONArray("questions") ?: return emptyList()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (obj.isNull("image")) continue
            val path = obj.optString("image", "").trim()
            if (path.isEmpty()) continue
            val base = path.substringAfterLast('/').trim()
            if (base.isNotEmpty()) names.add(base)
        }
        return names.toList()
    }

    /** 把索引里的 file 解析成完整地址：已带 http(s) 就直接用，否则相对源根目录 */
    private fun resolveFile(source: SourceConfig, file: String): URL {
        val trimmed = file.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return makeUrl(trimmed, "试卷地址")
        }
        return makeUrl(source.dirText() + trimmed, "试卷地址")
    }

    /**
     * 建连接并设好超时与认证头。
     * 注意用 requestProperty 手工写 Basic 头，**不用** java.net.Authenticator：
     * 那个会在 401 时弹系统框，而我们要的是自己给中文提示。
     */
    private fun openConnection(source: SourceConfig, url: URL, method: String): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        if (source.user.isNotEmpty() || source.pass.isNotEmpty()) {
            val token = Base64.encodeToString(
                "${source.user}:${source.pass}".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
    }

    /** MKCOL 一个目录；失败只记日志（真正的写失败交给 PUT 报） */
    private fun mkcol(source: SourceConfig, url: URL) {
        val conn = try {
            openConnection(source, url, "MKCOL")
        } catch (e: Exception) {
            Log.w(TAG, "创建目录请求失败（忽略）：${e.message}")
            return
        }
        try {
            val code = conn.responseCode
            Log.i(TAG, "WebDAV MKCOL ${safeUrl(url)} -> HTTP $code")
            // 201 新建成功；405/301/302 已存在；409 父目录不存在（服务器会自己处理或让 PUT 报错）
        } catch (e: Exception) {
            Log.w(TAG, "创建目录失败（忽略，继续上传）：${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** 状态码 → 中文原因。只在这里定义一次，界面不重复拼。 */
    private fun statusMessage(code: Int, what: String): String = when (code) {
        HttpURLConnection.HTTP_UNAUTHORIZED ->
            "${what}失败：账号或应用密码不对（HTTP 401）"

        HttpURLConnection.HTTP_FORBIDDEN ->
            "${what}失败：服务器拒绝访问（HTTP 403）"

        HttpURLConnection.HTTP_NOT_FOUND ->
            "${what}失败：题库源为空或文件不存在（HTTP 404），路径或文件名可能写错了"

        HttpURLConnection.HTTP_BAD_METHOD, HttpURLConnection.HTTP_CONFLICT ->
            "${what}失败：服务器不允许这样访问（HTTP $code）"

        in 500..599 -> "${what}失败：题库服务器出错（HTTP $code），请稍后再试"
        else -> "${what}失败：服务器返回 HTTP $code"
    }

    /** 上传专用的状态码 → 中文原因（409 在这里的含义是「目录不存在」） */
    private fun putStatusMessage(code: Int, what: String): String = when (code) {
        HttpURLConnection.HTTP_UNAUTHORIZED ->
            "$what 失败：账号或应用密码不对（HTTP 401）"

        HttpURLConnection.HTTP_FORBIDDEN ->
            "$what 失败：服务器拒绝访问（HTTP 403），这个账号可能没有写入权限"

        HttpURLConnection.HTTP_NOT_FOUND ->
            "$what 失败：服务器上找不到这个路径（HTTP 404），请检查地址与路径"

        HttpURLConnection.HTTP_CONFLICT ->
            "$what 失败：服务器上的目录不存在（HTTP 409），请先确认服务器地址与路径"

        in 500..599 -> "$what 失败：题库服务器出错（HTTP $code），请稍后再试"
        else -> "$what 失败：服务器返回 HTTP $code"
    }

    /**
     * 日志里用的地址：把 user:pass@ 这一段打码成星号。
     * 内置地址本来没有 userinfo，这一步是给「地址里手写了凭据」兜底的。
     * 注意：这里的注释里不能出现斜杠加星号，否则会被当成嵌套块注释的开头。
     */
    private fun safeUrl(url: URL): String =
        url.toString().replace(Regex("//[^/@]+@"), "//" + "******@")
}
