// 数据层（11 新增）：多题库源。
//
// 为什么单独一份 sources.json，而不是塞进 SharedPreferences：
//   1. 题库源从「一个内置常量 + 一个可选自定义地址」变成「一个列表 + 当前选中项」，
//      列表要能增删改，放文件里方便整体备份、也方便调试模式导出诊断；
//   2. 「默认源被删掉之后要显示恢复按钮」需要一个能持久化的存在性判断，
//      而默认源的三条常量永远留在代码里（WebDavSource.WEBDAV_*），删了也能原样恢复。
//
// 文件结构（任务书第 1 条，字段名不许改）：
// {
//   "currentSourceId": "builtin",
//   "sources": [
//     { "id","name","type","baseUrl","user","pass","path","uploadable","hiddenConfig" }
//   ]
// }
//
// 安全边界（硬性要求 1、3）：
//   - builtin 源的 baseUrl / user / pass / path 写在文件里（结构上与自定义源一致），
//     但界面上一律不显示（见 hiddenConfig 与 ui/SourceScreen.kt 的判定）；
//   - 本文件的所有日志只打源 id、名字与数量，绝不打 pass。
//
// 线程：读写都是小文件（<4KB），本类内部用 synchronized 做互斥；
//      界面层仍然建议在 Dispatchers.IO 上调用（MainActivity / 源管理页都是这么用的）。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个题库源。
 *
 * @param id 稳定 id：内置默认源固定 `builtin`，自定义源是 `u_<秒级时间戳>`
 * @param name 界面显示名（默认源固定「默认源」）
 * @param type `builtin` / `custom`
 * @param baseUrl 服务器根地址（含 dav 前缀），如 https://dav.jianguoyun.com/dav/
 * @param user 账号
 * @param pass 密码（红线：只在代码常量与 sources.json 里出现，日志/界面/报告一律不出现）
 * @param path 相对根目录，可空；如 `shuaati/`
 * @param uploadable 是否允许作为「上传本地试卷」的目标；默认源**必须**是 false
 * @param hiddenConfig 是否在界面上隐藏地址/账号/密码；默认源是 true
 */
data class SourceConfig(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val user: String,
    val pass: String,
    val path: String,
    val uploadable: Boolean,
    val hiddenConfig: Boolean,
) {
    /** 是不是内置默认源 */
    val isBuiltin: Boolean get() = type == TYPE_BUILTIN

    /**
     * 这个源的根目录文本（baseUrl + path，保证以 / 结尾）。
     * **不含账号密码**，所以可以放心显示在界面上。
     */
    fun dirText(): String {
        val base = baseUrl.trim()
        if (base.isEmpty()) return path.trim()
        val withSlash = if (base.endsWith("/")) base else "$base/"
        val rawPath = path.trim()
        if (rawPath.isEmpty()) return withSlash
        val normalized = rawPath.trimStart('/').let { if (it.endsWith("/")) it else "$it/" }
        return withSlash + normalized
    }

    /** 界面显示用的地址（自定义源才显示；调用方要先判断 hiddenConfig / isBuiltin） */
    fun displayAddress(): String = dirText()

    /** 写进 sources.json 的 JSON；字段名与任务书一致，不许改名 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("baseUrl", baseUrl)
        put("user", user)
        put("pass", pass)
        put("path", path)
        put("uploadable", uploadable)
        put("hiddenConfig", hiddenConfig)
    }

    companion object {
        /** 源类型：内置默认源 */
        const val TYPE_BUILTIN = "builtin"

        /** 源类型：用户自建 */
        const val TYPE_CUSTOM = "custom"

        /** 内置默认源固定 id */
        const val BUILTIN_ID = "builtin"

        /** 内置默认源在界面上的名字 */
        const val BUILTIN_NAME = "默认源"

        /** 从 sources.json 的一条解析；字段缺失按「自定义源」兜底，id 一定兜住 */
        fun fromJson(obj: JSONObject, fallbackId: String): SourceConfig {
            val id = obj.optString("id", "").trim().ifBlank { fallbackId }
            val type = obj.optString("type", "").trim().let {
                if (it == TYPE_BUILTIN || it == TYPE_CUSTOM) it else TYPE_CUSTOM
            }
            val isBuiltin = id == BUILTIN_ID || type == TYPE_BUILTIN
            return SourceConfig(
                id = id,
                name = obj.optString("name", "").trim().ifBlank { if (isBuiltin) BUILTIN_NAME else id },
                type = if (isBuiltin) TYPE_BUILTIN else TYPE_CUSTOM,
                baseUrl = obj.optString("baseUrl", "").trim(),
                user = obj.optString("user", ""),
                pass = obj.optString("pass", ""),
                path = obj.optString("path", "").trim(),
                // 默认源绝不可上传：就算文件被手改成 true，这里也强制压回 false
                uploadable = !isBuiltin && obj.optBoolean("uploadable", true),
                hiddenConfig = isBuiltin || obj.optBoolean("hiddenConfig", false),
            )
        }

        /**
         * 内置默认源（恢复默认源时用这一份重建）。
         * 地址/账号/密码全部来自 WebDavSource 里的 WEBDAV_* 常量 —— 那几条常量是
         * 「默认源被删掉之后还能恢复」的唯一依据，**不许删**。
         */
        fun builtinDefault(): SourceConfig = SourceConfig(
            id = BUILTIN_ID,
            name = BUILTIN_NAME,
            type = TYPE_BUILTIN,
            baseUrl = WEBDAV_BASE_URL,
            user = WEBDAV_USER,
            pass = WEBDAV_PASS,
            path = WEBDAV_PATH,
            uploadable = false,
            hiddenConfig = true,
        )
    }
}

/** sources.json 的完整内容 */
data class SourceSnapshot(
    val currentSourceId: String?,
    val sources: List<SourceConfig>,
) {
    /** 当前源；currentSourceId 指向一个已经不存在的源时返回 null（界面自己提示） */
    fun current(): SourceConfig? = sources.firstOrNull { it.id == currentSourceId }

    fun byId(id: String?): SourceConfig? = sources.firstOrNull { it.id == id }

    /** 默认源还在不在（不在时源管理页显示「恢复默认源」） */
    fun hasBuiltin(): Boolean = sources.any { it.isBuiltin }

    /**
     * 可以作为「上传本地试卷」目标的源。
     * 硬性要求 1：默认源绝不出现在这个列表里，所以这里同时按
     * `uploadable == true` 和 `!isBuiltin` 两个条件过滤（双保险，防止文件被手改）。
     */
    fun uploadTargets(): List<SourceConfig> =
        sources.filter { it.uploadable && !it.isBuiltin && !it.hiddenConfig }

    /** 某个源下有几套试卷（按 meta.sourceId 归属统计） */
    fun paperCount(papers: List<PaperMeta>, sourceId: String): Int =
        papers.count { it.sourceId == sourceId }
}

/**
 * sources.json 的读写器。
 *
 * 设计上只有两种失败会被吞掉：
 *   - 文件不存在 → 建一份「只有默认源」的；
 *   - 文件读坏 → 先留档成 sources.json.broken-<时间戳>，再按「只有默认源」重建。
 * 其它情况（写盘失败）只记日志，内存里的快照仍然返回，保证界面不白屏。
 */
class SourceStore(context: Context) {

    private val tag = "Shuaati"

    /** sources.json 的绝对路径（调试模式要显示它） */
    val filePath: String = File(context.filesDir, "sources.json").absolutePath

    private val file = File(context.filesDir, "sources.json")

    private val lock = Any()

    /**
     * 全进程的源配置版本号（v1.5）：
     * 调试端口会用另一个 SourceStore 实例改源，如果只靠每个实例各自的内存缓存，
     * 界面这边就看不到新源（上传对话框会显示「还没有可上传的源」）。
     * 任何实例写盘后 revision +1，其它实例 load() 发现版本对不上就重读文件。
     */
    companion object {
        @Volatile
        private var revision: Long = 0L
    }

    /** 内存快照；为 null 表示还没读过 */
    private var cache: SourceSnapshot? = null

    /** 缓存对应的 revision；和全局 revision 不一致就说明别的实例改过源 */
    private var cachedRevision: Long = -1L

    /** 读当前配置（第一次调用会顺手把文件建出来） */
    fun load(): SourceSnapshot = synchronized(lock) {
        val current = revision
        val cached = cache
        if (cached != null && cachedRevision == current) {
            cached
        } else {
            read().also {
                cache = it
                cachedRevision = current
            }
        }
    }

    /** 强制重读（源管理页每次操作后都靠它拿最新状态） */
    fun reload(): SourceSnapshot = synchronized(lock) {
        cache = null
        read().also {
            cache = it
            cachedRevision = revision
        }
    }

    /** 切当前源；sourceId 必须存在，否则原样返回 */
    fun setCurrent(sourceId: String?): SourceSnapshot {
        val snapshot = load()
        if (sourceId != null && snapshot.byId(sourceId) == null) {
            Log.w(tag, "切换当前源失败：sources 里没有 $sourceId")
            return snapshot
        }
        return persist(snapshot.copy(currentSourceId = sourceId))
    }

    /**
     * 新增或更新一个源。
     * @param makeCurrent true = 顺便设为当前源（新建源、恢复默认源时都用 true；编辑时用 false）
     */
    fun save(source: SourceConfig, makeCurrent: Boolean): SourceSnapshot {
        val snapshot = load()
        val list = snapshot.sources.toMutableList()
        val index = list.indexOfFirst { it.id == source.id }
        if (index >= 0) list[index] = source else list.add(source)
        val current = if (makeCurrent || snapshot.currentSourceId == null) {
            source.id
        } else {
            snapshot.currentSourceId
        }
        Log.i(tag, "保存源：id=${source.id}，名字=${source.name}，共 ${list.size} 个源")
        return persist(SourceSnapshot(current, list))
    }

    /**
     * 删除一个源。
     * 删的是当前源时自动切到列表里第一个源；一个都不剩时当前源置空（界面会提示）。
     */
    fun delete(sourceId: String): SourceSnapshot {
        val snapshot = load()
        val list = snapshot.sources.filterNot { it.id == sourceId }
        val current = if (snapshot.currentSourceId == sourceId) {
            list.firstOrNull()?.id
        } else {
            snapshot.currentSourceId
        }
        Log.i(tag, "删除源：id=$sourceId，剩余 ${list.size} 个，当前=$current")
        return persist(SourceSnapshot(current, list))
    }

    /** 把内置默认源原样加回来，并设为当前源（社区版没有内置源，直接返回原快照） */
    fun restoreBuiltin(): SourceSnapshot {
        val snapshot = load()
        if (!HAS_BUILTIN_SOURCE || snapshot.hasBuiltin()) return snapshot
        return persist(
            SourceSnapshot(
                currentSourceId = SourceConfig.BUILTIN_ID,
                sources = snapshot.sources + SourceConfig.builtinDefault(),
            )
        )
    }

    /**
     * 确保默认源存在并设为当前源（引导页「使用默认源」用）。
     * @return 默认源配置（restoreBuiltin 之后从内存快照里取，保证拿到的是同一份）
     */
    fun ensureBuiltinCurrent(): SourceConfig {
        // 社区版没有内置源：直接报错（调用方是引导页「使用默认源」，该入口在社区版已隐藏）
        if (!HAS_BUILTIN_SOURCE) {
            throw IllegalStateException("这是社区版，没有内置题库源；请新建一个自己的源，或本地导入试卷")
        }
        val snapshot = if (load().hasBuiltin()) load() else restoreBuiltin()
        if (snapshot.currentSourceId != SourceConfig.BUILTIN_ID) setCurrent(SourceConfig.BUILTIN_ID)
        return SourceConfig.builtinDefault()
    }

    /** 生成一个不与现有源冲突的自定义源 id：u_<秒级时间戳>，冲突时加 -2、-3 */
    fun newCustomId(): String {
        val stamp = System.currentTimeMillis() / 1000L
        var id = "u_$stamp"
        var n = 2
        while (load().byId(id) != null) {
            id = "u_$stamp-$n"
            n++
        }
        return id
    }

    // ===== 下面都是文件层 =====

    private fun read(): SourceSnapshot {
        if (!file.isFile) {
            // v1.5：社区版没有内置源 → 首次创建就是空列表，等用户自己新增源
            val snapshot = if (HAS_BUILTIN_SOURCE) {
                SourceSnapshot(
                    currentSourceId = SourceConfig.BUILTIN_ID,
                    sources = listOf(SourceConfig.builtinDefault()),
                )
            } else {
                SourceSnapshot(currentSourceId = null, sources = emptyList())
            }
            write(snapshot)
            Log.i(
                tag,
                if (HAS_BUILTIN_SOURCE) {
                    "首次创建 sources.json：只含默认源 -> $filePath"
                } else {
                    "首次创建 sources.json：社区版无内置源，等用户新增 -> $filePath"
                },
            )
            return snapshot
        }
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("sources") ?: JSONArray()
            val list = ArrayList<SourceConfig>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val source = SourceConfig.fromJson(obj, "source-$i")
                // v1.5：社区版没有内置源——从特供版切过来时，遗留的默认源条目直接丢掉
                if (source.isBuiltin && !HAS_BUILTIN_SOURCE) {
                    Log.i(tag, "社区版忽略遗留的默认源条目：${source.id}")
                    continue
                }
                // 自定义源没有服务器地址等于不可用，直接跳过，不让界面出现点不动的行
                if (!source.isBuiltin && source.baseUrl.isBlank()) {
                    Log.w(tag, "sources.json 里有一条自建源缺少 baseUrl，已跳过：${source.id}")
                    continue
                }
                list.add(source)
            }
            val rawCurrent = if (root.isNull("currentSourceId")) {
                null
            } else {
                root.optString("currentSourceId", "").trim().takeIf { it.isNotEmpty() }
            }
            val current = if (list.any { it.id == rawCurrent }) rawCurrent else list.firstOrNull()?.id
            if (rawCurrent != null && current != rawCurrent) {
                Log.w(tag, "currentSourceId=$rawCurrent 已不存在，临时改用 $current")
            }
            Log.i(tag, "sources loaded: ${list.size} 个源，当前=$current")
            SourceSnapshot(currentSourceId = current, sources = list)
        } catch (e: Exception) {
            // 文件坏了：先留档，再重建，避免 App 因为一个坏 JSON 起不来
            val broken = File(file.parentFile, "sources.json.broken-${stamp()}")
            runCatching { file.copyTo(broken, overwrite = true) }
            Log.e(tag, "sources.json 读取失败，已留档到 ${broken.name}，按默认源重建", e)
            val snapshot = SourceSnapshot(
                currentSourceId = SourceConfig.BUILTIN_ID,
                sources = listOf(SourceConfig.builtinDefault()),
            )
            write(snapshot)
            snapshot
        }
    }

    private fun persist(snapshot: SourceSnapshot): SourceSnapshot = synchronized(lock) {
        write(snapshot)
        revision++
        cache = snapshot
        cachedRevision = revision
        snapshot
    }

    /** 先写临时文件再改名，避免写到一半被杀留下半个 JSON */
    private fun write(snapshot: SourceSnapshot) {
        val root = JSONObject()
        root.put("currentSourceId", snapshot.currentSourceId ?: JSONObject.NULL)
        val array = JSONArray()
        snapshot.sources.forEach { array.put(it.toJson()) }
        root.put("sources", array)
        val text = root.toString(2)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "sources.json.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 个别文件系统不支持覆盖式改名，退回直接写
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(tag, "sources.json 写盘失败：$filePath", e)
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
