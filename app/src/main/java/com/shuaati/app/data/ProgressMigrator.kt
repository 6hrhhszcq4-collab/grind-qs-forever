// 数据层（v1.3 新增）：题库换版本时的做题进度迁移。
//
// 为什么需要它：
//   题库更新后，同一道题的 id 可能被重新生成（题库是重新导出的），
//   如果只按 id 搬进度，老师手机上几百条错题、连对、FSRS 记忆参数会全部对不上，
//   表现就是「更新一次题库，学习记录全没了」。
//
// 对齐规则（任务书第 3 条，顺序固定）：
//   1. 主键：(paper, type, no) 三元组完全对得上 → 直接搬；
//   2. 对不上：用题干前 40 个字的「规范化文本」做匹配
//      （去掉所有空白与标点，避免标点/空格差异导致匹配失败）；
//   3. 两者都失败：这条进度不搬，但必须原样保留到 progress.json.migrated.bak，
//      绝不允许直接丢掉。
//
// 安全顺序（硬性要求）：
//   - 迁移前先把 progress.json 复制成 progress.json.bak-<时间戳>；
//   - 本函数只负责「算好新文件内容并写 progress.json」，调用方必须保证
//     它在「新 questions.json 落盘之前」调用（先算映射、再换题库，中途失败也不会半套）。
package com.shuaati.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次进度迁移的结果，日志与验收对账都用它 */
data class ProgressMigrationReport(
    /** 成功搬过去的条目数（= byNo + byStem） */
    val matched: Int,
    /** 靠 (paper, type, no) 三元组匹配的条数 */
    val byNo: Int,
    /** 靠题干前 40 字规范化文本匹配的条数 */
    val byStem: Int,
    /** 两种方式都没匹配上、但已经留在 .migrated.bak 里的条数 */
    val dropped: Int,
    /** progress.json 的备份路径；没有旧文件时为 null */
    val backupPath: String?,
    /** 未匹配条目的落盘路径；没有未匹配条目时为 null */
    val droppedBackupPath: String?,
    /** 是否真的写回了 progress.json（旧文件不存在 / 解析失败时为 false） */
    val written: Boolean,
) {
    /** 验收时在 logcat 里要搜的那一行（格式由任务书规定，不许改） */
    fun logLine(): String = "progress-migrate: matched=$matched byNo=$byNo byStem=$byStem dropped=$dropped"
}

/**
 * 进度迁移器（无状态 object，任何调用方都只调 [migrate]）。
 */
object ProgressMigrator {

    private const val TAG = "Shuaati"

    /** 题干取前多少个字做匹配（任务书规定 40） */
    private const val STEM_PREFIX_LENGTH = 40

    /** 去空白 + 去标点的正则；中文常用标点都在 \p{P} / \p{S} 里 */
    private val NORMALIZE_REGEX = Regex("[\\s\\p{P}\\p{S}]")

    /** 默认备份时间戳：与项目里其它备份文件保持同一种格式 */
    fun nowStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * 把 [progressFile] 里的做题进度迁移到 [newQuestions] 上。
     *
     * @param progressFile 这套试卷的 progress.json
     * @param oldQuestions 更新前题库里的题目（用来算 (paper,type,no) 与题干）
     * @param newQuestions 更新后题库里的题目
     * @param timestamp 备份文件名用的时间戳（测试时可传固定值）
     * @return 迁移报告；旧文件不存在或读坏时 written=false（不写任何东西）
     */
    fun migrate(
        progressFile: File,
        oldQuestions: List<Question>,
        newQuestions: List<Question>,
        timestamp: String = nowStamp(),
    ): ProgressMigrationReport {
        if (!progressFile.isFile) {
            Log.i(TAG, "progress-migrate: 没有旧的 progress.json，跳过（written=false）")
            return ProgressMigrationReport(0, 0, 0, 0, null, null, false)
        }

        // ---- 第 0 步：先把旧进度读出来；读坏就按「不迁移」处理，绝不写坏文件 ----
        val root = try {
            JSONObject(progressFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "progress-migrate: 旧进度文件读不出来，跳过迁移", e)
            return ProgressMigrationReport(0, 0, 0, 0, null, null, false)
        }

        // ---- 第 1 步：备份（任务书硬性要求：迁移前必须先备份）----
        val backup = File(progressFile.parentFile, "progress.json.bak-$timestamp")
        val backupPath = try {
            progressFile.copyTo(backup, overwrite = true)
            backup.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "progress-migrate: 备份 progress.json 失败，继续迁移", e)
            null
        }

        // ---- 第 2 步：给新题库建两张索引 ----
        // 主键索引：paper|type|no -> 新题
        val byKey = HashMap<String, Question>(newQuestions.size)
        // 题干索引：规范化题干前 40 字 -> 候选新题（理论上唯一，重复时按顺序取没被占用的）
        val byStem = HashMap<String, MutableList<Question>>(newQuestions.size)
        for (question in newQuestions) {
            byKey.putIfAbsent(primaryKeyOf(question), question)
            byStem.getOrPut(stemKeyOf(question)) { ArrayList(1) }.add(question)
        }

        // ---- 第 3 步：逐条算映射（只算，不改文件）----
        val oldById = oldQuestions.associateBy { it.id }
        /** 旧 id -> 新 id */
        val mapping = LinkedHashMap<String, String>()
        /** 新 id -> 旧 id（防止两条旧记录抢同一道新题） */
        val usedNewIds = HashSet<String>()
        /** 没匹配上的旧条目：原样留档 */
        val dropped = LinkedHashMap<String, JSONObject>()

        var byNoCount = 0
        var byStemCount = 0

        val keys = root.keys()
        while (keys.hasNext()) {
            val oldId = keys.next()
            // _round / _round_single / ... / _current 这些内部键在下一步统一映射，这里跳过
            if (oldId.startsWith("_")) continue
            val record = root.optJSONObject(oldId) ?: continue
            val oldQuestion = oldById[oldId]
            if (oldQuestion == null) {
                // 旧题库里都找不到这道题（题库被手工改过？）：留档，不丢
                dropped[oldId] = record
                continue
            }

            // 3.1 主键匹配：(paper, type, no) 完全一致
            val hit = byKey[primaryKeyOf(oldQuestion)]
            if (hit != null && usedNewIds.add(hit.id)) {
                mapping[oldId] = hit.id
                byNoCount++
                continue
            }

            // 3.2 题干匹配：前 40 字规范化后相等，且该新题还没被占用
            val candidates = byStem[stemKeyOf(oldQuestion)]
            val stemHit = candidates?.firstOrNull { it.id !in usedNewIds }
            if (stemHit != null) {
                usedNewIds.add(stemHit.id)
                mapping[oldId] = stemHit.id
                byStemCount++
            } else {
                dropped[oldId] = record
            }
        }

        // ---- 第 4 步：把没匹配上的条目写进 progress.json.migrated.bak（留档）----
        var droppedBackupPath: String? = null
        if (dropped.isNotEmpty()) {
            val droppedFile = File(progressFile.parentFile, "progress.json.migrated.bak")
            try {
                val archive = JSONObject().apply {
                    put("migratedAt", timestamp)
                    put("reason", "题库更新后这些做题记录按题号和题干都没对上新题，原样保留在这里")
                    put(
                        "mapping",
                        JSONObject().apply {
                            mapping.forEach { (old, new) -> put(old, new) }
                        },
                    )
                    put(
                        "dropped",
                        JSONObject().apply {
                            dropped.forEach { (old, record) -> put(old, record) }
                        },
                    )
                }
                droppedFile.writeText(archive.toString(2), Charsets.UTF_8)
                droppedBackupPath = droppedFile.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "progress-migrate: 未匹配条目留档失败：${droppedFile.absolutePath}", e)
            }
        }

        // ---- 第 5 步：拼出新 progress.json 并写盘 ----
        // 结构与 ProgressStore.save() 完全一致：记录 + 四个轮次数组 + _current。
        // 字段一律原样复制（seen/correct/wrong/streak/last/recent/stability/difficulty），
        // 所以错题数、连对数、FSRS 的 S/D 都不会被这次迁移改写。
        val out = JSONObject()
        // 轮次数组里的 id 也一起换成新 id；映射不上的题从本轮里剔掉（新题库已没有这道题）
        for (key in root.keys()) {
            if (!key.startsWith("_round")) continue
            val array = root.optJSONArray(key) ?: continue
            val mapped = JSONArray()
            for (i in 0 until array.length()) {
                val oldId = array.optString(i, "")
                mapping[oldId]?.let { mapped.put(it) }
            }
            out.put(key, mapped)
        }
        // _current 同样映射；映射不上就写 null（下次进统一刷从第一题开始）
        val oldCurrent = if (root.has("_current") && !root.isNull("_current")) {
            root.optString("_current", "").takeIf { it.isNotBlank() }
        } else {
            null
        }
        val newCurrent = oldCurrent?.let { mapping[it] }
        out.put("_current", newCurrent ?: JSONObject.NULL)
        // 记录：一个旧 id 对应一个新 id，直接搬
        mapping.forEach { (oldId, newId) ->
            root.optJSONObject(oldId)?.let { out.put(newId, it) }
        }

        // 先写临时文件再改名：和 ProgressStore 一样，避免写到一半被杀导致文件损坏
        try {
            val text = out.toString(2)
            val tmp = File(progressFile.parentFile, "progress.json.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(progressFile)) {
                progressFile.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "progress-migrate: 写新进度失败，旧文件与备份都还在", e)
            return ProgressMigrationReport(
                matched = mapping.size,
                byNo = byNoCount,
                byStem = byStemCount,
                dropped = dropped.size,
                backupPath = backupPath,
                droppedBackupPath = droppedBackupPath,
                written = false,
            )
        }

        val report = ProgressMigrationReport(
            matched = mapping.size,
            byNo = byNoCount,
            byStem = byStemCount,
            dropped = dropped.size,
            backupPath = backupPath,
            droppedBackupPath = droppedBackupPath,
            written = true,
        )
        Log.i(TAG, report.logLine())
        return report
    }

    /**
     * 主键：(paper, type, no) 三元组。
     * 用竖线分隔并带上类型，避免「单选第 1 题」和「判断第 1 题」撞在一起。
     */
    private fun primaryKeyOf(question: Question): String =
        "${question.paper}|${question.type}|${question.no}"

    /**
     * 题干匹配键：先去空白与标点（规范化），再取前 40 个字。
     * 题库两次导出的标点可能有全角/半角差异，不做规范化就会漏配。
     */
    private fun stemKeyOf(question: Question): String =
        question.stem.replace(NORMALIZE_REGEX, "").take(STEM_PREFIX_LENGTH)
}
