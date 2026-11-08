// 数据层：一套试卷的基本信息，就是 papers\<paperId>\meta.json 的内容。
//
// 为什么单独存一份 meta.json 而不是每次去数 questions.json：
//   试卷列表页要显示「名称 + 题数」，如果每次都解析 21 万字节的题库，进设置页会明显卡顿。
//
// 11 新增三个字段（任务书第 2 条）：
//   sourceId —— 来自哪个源；本地导入为 null
//   origin   —— cloud / local
//   version  —— 云端版本号；本地导入为 null（启动更新检查靠它比大小）
package com.shuaati.app.data

import org.json.JSONObject

/**
 * 一套试卷的元信息。
 *
 * @param id 短 slug，同时是目录名（如 hanjie-gaoji-202610）
 * @param name 界面上显示的试卷名（如「焊工高级 2026.10」）
 * @param total 题数
 * @param singleCount / @param judgeCount / @param multiCount 三种题型的题量
 * @param source 来源文案：「内置默认卷」「本地导入」「网络下载」「上传到「X」」
 * @param importedAt 导入时间 yyyy-MM-dd HH:mm（内置卷为迁移时间）
 * @param sourceId 来自哪个源（SourceConfig.id）；本地导入为 null
 * @param origin `cloud`（来自源）/ `local`（本地导入、还没上传）
 * @param version 云端版本号；本地导入为 null
 * @param remoteId v1.3 新增：这套卷在「源」上的 paperId（例如 hanjie-gaoji-202610）。
 *                 下载/更新时用它跟公网版本文件里的 paperId 对齐——只认 paperId，
 *                 不再靠「名字相同」这种一改就断的办法。本地导入/老数据为 null，
 *                 由 UpdateChecker 首次拿到 papers.json 时按名称补一次。
 */
data class PaperMeta(
    val id: String,
    val name: String,
    val total: Int,
    val singleCount: Int,
    val judgeCount: Int,
    val multiCount: Int,
    val source: String,
    val importedAt: String,
    val sourceId: String? = null,
    val origin: String = ORIGIN_CLOUD,
    val version: Int? = null,
    val remoteId: String? = null,
) {
    /** 「共 300 题 · 单选 220 / 判断 60 / 多选 20」这种一行说明，界面与对话框共用 */
    fun summary(): String = "共 $total 题 · 单选 $singleCount / 判断 $judgeCount / 多选 $multiCount"

    /** 是不是只在本地的卷（可以「上传到源」，列表里打「本地」标签） */
    val isLocal: Boolean get() = origin == ORIGIN_LOCAL

    /** 写进 meta.json 的 JSON；字段名与任务书一致，不许改名 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("total", total)
        put("counts", JSONObject().apply {
            put("single", singleCount)
            put("judge", judgeCount)
            put("multi", multiCount)
        })
        put("source", source)
        put("importedAt", importedAt)
        // 11 新增的三个来源字段：null 就写 JSON 的 null（不省略键），
        // 这样「没有这个键」就只可能是 10 及更早版本写的老文件，迁移判定不会错
        put("sourceId", sourceId ?: JSONObject.NULL)
        put("origin", origin)
        put("version", version ?: JSONObject.NULL)
        // v1.3：源上的 paperId。同样写成 JSON null 而不是省略键，
        // 这样「没有这个键」就只可能是更早版本写的老文件。
        put("remoteId", remoteId ?: JSONObject.NULL)
    }

    companion object {
        /** 来自源 */
        const val ORIGIN_CLOUD = "cloud"

        /** 只在本地（本地导入、还没上传） */
        const val ORIGIN_LOCAL = "local"

        /**
         * 从 meta.json 解析。
         * 字段缺失时尽量兜底（老版本手写的 meta.json 也能读）：
         *   - origin 缺失 → cloud
         *   - sourceId 缺失 → builtin（老数据全部归属内置默认源，任务书第 2 条明确要求）
         *   - version 缺失 → null
         * id 一定用目录名兜住，保证「改名」不会让试卷失联。
         */
        fun fromJson(obj: JSONObject, fallbackId: String): PaperMeta {
            val counts = obj.optJSONObject("counts")
            val origin = obj.optString("origin", "").trim().let {
                if (it == ORIGIN_LOCAL || it == ORIGIN_CLOUD) it else ORIGIN_CLOUD
            }
            val sourceId = when {
                obj.isNull("sourceId") -> null
                obj.has("sourceId") -> obj.optString("sourceId", "").trim().takeIf { it.isNotEmpty() }
                else -> SourceConfig.BUILTIN_ID // 老文件没有这个键：归到内置默认源
            }
            val version = if (obj.has("version") && !obj.isNull("version")) {
                obj.optInt("version", 0)
            } else {
                null
            }
            // v1.3：源上的 paperId；老文件没有这个键就是 null（由更新检查按名称补一次）
            val remoteId = if (obj.isNull("remoteId")) {
                null
            } else {
                obj.optString("remoteId", "").trim().takeIf { it.isNotEmpty() }
            }
            return PaperMeta(
                id = obj.optString("id", "").trim().ifBlank { fallbackId },
                name = obj.optString("name", "").trim().ifBlank { fallbackId },
                total = obj.optInt("total", 0),
                singleCount = counts?.optInt("single", 0) ?: 0,
                judgeCount = counts?.optInt("judge", 0) ?: 0,
                multiCount = counts?.optInt("multi", 0) ?: 0,
                source = obj.optString("source", "未知来源"),
                importedAt = obj.optString("importedAt", ""),
                sourceId = sourceId,
                origin = origin,
                version = version,
                remoteId = remoteId,
            )
        }

        /**
         * 按题目列表统计出一份 meta。
         * 内置卷迁移、本地导入、网络下载、云端更新四条路径都用这一个函数统计，
         * 保证四处的题数口径一致。
         */
        fun fromQuestions(
            id: String,
            name: String,
            questions: List<Question>,
            source: String,
            importedAt: String,
            sourceId: String? = null,
            origin: String = ORIGIN_CLOUD,
            version: Int? = null,
            remoteId: String? = null,
        ): PaperMeta = PaperMeta(
            id = id,
            name = name,
            total = questions.size,
            singleCount = questions.count { it.type == "single" },
            judgeCount = questions.count { it.type == "judge" },
            multiCount = questions.count { it.type == "multi" },
            source = source,
            importedAt = importedAt,
            sourceId = sourceId,
            origin = origin,
            version = version,
            remoteId = remoteId,
        )
    }
}
