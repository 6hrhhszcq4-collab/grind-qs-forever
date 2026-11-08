// 数据层（06B 新增）：试卷库。
//
// 目录约定（任务书第 1 条）：
//   filesDir\papers\<paperId>\questions.json  题库
//   filesDir\papers\<paperId>\images\         题目图片
//   filesDir\papers\<paperId>\progress.json   这套试卷的做题进度
//   filesDir\papers\<paperId>\exam.json       这套试卷没考完的模拟考试
//   filesDir\papers\<paperId>\meta.json       {id,name,total,counts,source,importedAt}
//
// 当前试卷的 id 存 SharedPreferences（键 currentPaperId），切卷只改这一个值 + 重建会话对象，
// 所以每套试卷的 _round / _current / 错题本 / 智能刷 recent 天然是分开的。
//
// 首次启动迁移是本文件最要紧的一段：先备份旧 progress.json，再复制（不是移动）旧文件，
// 旧文件一律保留，出问题可以整目录回滚。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次迁移做完后的结果，App 启动时打日志、验收时对账用 */
data class MigrationReport(
    /** 迁移出的试卷 id */
    val paperId: String,
    /** 旧 progress.json 里的题目记录条数（不含 _round / _current 这些内部键） */
    val progressCount: Int,
    /** 旧 progress.json 里统一刷本轮 _round 的长度 */
    val roundLength: Int,
    /** 备份文件路径；旧文件不存在时为 null */
    val backupPath: String?,
    /** 复制进试卷目录的图片张数 */
    val imageCount: Int,
    /** 旧 exam.json 是否也复制过去了 */
    val examCopied: Boolean,
)

/**
 * 试卷库读写器。
 * 只负责「有哪些试卷、当前是哪套、删一套」以及首次启动迁移；题目解析交给 QuestionRepo，
 * 导入校验交给 PaperImporter，互不越界。
 */
class PaperRepo(private val context: Context) {

    private val tag = "Shuaati"

    /** 所有试卷的父目录：filesDir\papers */
    val root: File = File(context.filesDir, "papers")

    private val filesDir: File = context.filesDir
    private val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    /** 内置卷的目录/信息 */
    fun dirOf(id: String): File = File(root, id)
    fun questionsFile(id: String): File = File(dirOf(id), "questions.json")
    fun progressFile(id: String): File = File(dirOf(id), "progress.json")
    fun examFile(id: String): File = File(dirOf(id), "exam.json")
    fun metaFile(id: String): File = File(dirOf(id), "meta.json")
    fun imagesDir(id: String): File = File(dirOf(id), "images")

    /** 读一套试卷的 meta；没有或解析失败返回 null（坏目录直接从列表里跳过，不让界面崩） */
    fun metaOf(id: String): PaperMeta? {
        val file = metaFile(id)
        if (!file.isFile) return null
        return try {
            PaperMeta.fromJson(JSONObject(file.readText(Charsets.UTF_8)), id)
        } catch (e: Exception) {
            Log.w(tag, "meta.json 读取失败，已跳过：${file.absolutePath}", e)
            null
        }
    }

    /** 写 meta.json；父目录不存在时先建 */
    fun writeMeta(meta: PaperMeta) {
        val file = metaFile(meta.id)
        file.parentFile?.mkdirs()
        file.writeText(meta.toJson().toString(2), Charsets.UTF_8)
    }

    /**
     * 列出全部试卷，按导入时间升序（内置默认卷永远排第一）。
     * 只认带 meta.json 的目录：半途失败留下的空目录不会出现在列表里。
     */
    fun list(): List<PaperMeta> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val list = ArrayList<PaperMeta>(dirs.size)
        for (dir in dirs) {
            metaOf(dir.name)?.let { list.add(it) }
        }
        return list.sortedBy { it.importedAt }
    }

    /**
     * 当前试卷 id。
     * 存的 id 指向的目录被删/被破坏时自动退到列表第一套，并把这个修正写回 SharedPreferences，
     * 避免「删了当前卷之后 App 一直找不到题库」。
     */
    fun currentId(): String? {
        val saved = prefs.getString(KEY_CURRENT_PAPER, null)
        if (saved != null && dirOf(saved).isDirectory && questionsFile(saved).isFile) return saved
        val first = list().firstOrNull() ?: return null
        setCurrent(first.id)
        return first.id
    }

    /**
     * 切换当前试卷。
     * 用 commit() 而不是 apply()：切卷必须立刻落盘。
     * 实测过一次「刚切到导入的试卷就被 force-stop，重开又回到焊工卷」——就是 apply 的异步写丢了。
     */
    fun setCurrent(id: String) {
        prefs.edit().putString(KEY_CURRENT_PAPER, id).commit()
    }

    /** 当前试卷的 meta；没有试卷时返回 null */
    fun current(): PaperMeta? = currentId()?.let { metaOf(it) }

    /**
     * 删除一套试卷（整个目录）。
     * 删的是当前卷时自动切到剩下的第一套；一套都不剩时清掉 currentPaperId。
     * @return 是否真的删掉了
     */
    fun delete(id: String): Boolean {
        val dir = dirOf(id)
        if (!dir.isDirectory) return false
        val ok = try {
            dir.deleteRecursively()
        } catch (e: Exception) {
            Log.e(tag, "删除试卷目录失败：${dir.absolutePath}", e)
            false
        }
        if (ok && prefs.getString(KEY_CURRENT_PAPER, null) == id) {
            val first = list().firstOrNull()
            if (first != null) {
                setCurrent(first.id)
            } else {
                prefs.edit().remove(KEY_CURRENT_PAPER).commit()
            }
        }
        Log.i(tag, "删除试卷 $id -> $ok")
        return ok
    }

    /**
     * 给新导入的试卷生成一个不冲突的短 slug。
     * 形式 import-20261101-215000，同一秒导入两套时自动加 -2、-3。
     */
    fun newPaperId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var id = "import-$stamp"
        var n = 2
        while (dirOf(id).exists()) {
            id = "import-$stamp-$n"
            n++
        }
        return id
    }

    /** 「2026-11-01 21:50」这种给人看的时间，写进 meta.json */
    fun nowText(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    /**
     * 启动时的「试卷库准备」（v1.3 起不再从 assets 复制内置题库）。
     *
     * 现在的行为：
     *   - papers 目录里已经有试卷（v1.2 装过的用户）→ 什么都不做，照常用；
     *   - papers 是空的（全新安装 / 从 v1.1 直接升级）→ **不创建任何试卷**，
     *     只检查 filesDir 根目录有没有 v1.1 留下的旧做题记录，记一行日志。
     *     界面随后显示「还没有题库」空状态，由用户自己「从源下载」或「本地导入」。
     *
     * 为什么不在这里自动下载：启动时可能没网，而且下载要用户选源。
     *
     * @return 永远返回 null（保留旧返回值类型，调用方不用关心）
     */
    fun ensureMigrated(): MigrationReport? {
        val children = root.listFiles()
        if (!children.isNullOrEmpty()) {
            Log.i(tag, "已有多套试卷目录，跳过迁移：${children.size} 个条目")
            return null
        }
        // v1.3：安装包里不再内置题库，这里绝不创建试卷目录。
        if (hasLegacyData()) {
            Log.i(
                tag,
                "v1.3 首次启动：发现 v1.1 留下的做题记录但没有试卷（安装包已不再内置题库）；" +
                    "等用户从源下载或本地导入后，会自动把旧记录搬过去",
            )
        } else {
            Log.i(tag, "v1.3 首次启动：没有任何试卷，进入空状态")
        }
        return null
    }

    // ===== v1.3 新增：v1.1 旧数据兜底（filesDir 根目录的 progress.json / exam.json）=====

    /** v1.1 的旧做题记录文件（filesDir\progress.json）；v1.2 起改成每套试卷一份 */
    fun legacyProgressFile(): File = File(filesDir, "progress.json")

    /** v1.1 的旧考试断点文件（filesDir\exam.json） */
    fun legacyExamFile(): File = File(filesDir, "exam.json")

    /** 有没有 v1.1 留下的旧数据（有的话，新试卷落地后要自动搬过去） */
    fun hasLegacyData(): Boolean = legacyProgressFile().isFile || legacyExamFile().isFile

    /**
     * 把 v1.1 留在 filesDir 根目录的做题记录搬到 [targetId] 这套新试卷上。
     *
     * v1.1 的题库已经随安装包删掉了，没法按题干对齐，只能按**题目 id 直搬**；
     * id 对不上的条目原样写进 progress.json.migrated.bak，不丢。
     * 旧文件一律保留（复制，不移动），出问题可以整目录回滚。
     *
     * @param newQuestions 新试卷的题目（用来判断哪些 id 还活着）
     */
    fun migrateLegacyData(targetId: String, newQuestions: List<Question>): LegacyMigrationReport {
        val newIds = newQuestions.map { it.id }.toSet()
        var moved = 0
        var dropped = 0
        var progressMigrated = false
        var examMigrated = false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        // ---- 旧做题记录 ----
        val oldProgress = legacyProgressFile()
        if (oldProgress.isFile) {
            val root = try {
                JSONObject(oldProgress.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(tag, "旧 progress.json 读不出来，跳过旧数据迁移", e)
                null
            }
            if (root != null) {
                runCatching {
                    oldProgress.copyTo(File(filesDir, "progress.json.bak-$stamp"), overwrite = true)
                }
                val out = JSONObject()
                // 轮次数组：只留下新题库里还存在的 id
                for (key in root.keys()) {
                    if (!key.startsWith("_round")) continue
                    val array = root.optJSONArray(key) ?: continue
                    val kept = JSONArray()
                    for (i in 0 until array.length()) {
                        val id = array.optString(i, "")
                        if (id in newIds) kept.put(id)
                    }
                    out.put(key, kept)
                }
                // 当前停留的题
                val current = if (root.has("_current") && !root.isNull("_current")) {
                    root.optString("_current", "").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                out.put("_current", if (current != null && current in newIds) current else JSONObject.NULL)

                // 每道题的进度：id 对得上就搬，对不上留档
                val droppedObj = JSONObject()
                for (key in root.keys()) {
                    if (key.startsWith("_")) continue
                    val record = root.optJSONObject(key) ?: continue
                    if (key in newIds) {
                        out.put(key, record)
                        moved++
                    } else {
                        droppedObj.put(key, record)
                        dropped++
                    }
                }

                val target = progressFile(targetId)
                target.parentFile?.mkdirs()
                target.writeText(out.toString(2), Charsets.UTF_8)
                progressMigrated = true

                if (dropped > 0) {
                    val archive = JSONObject().apply {
                        put("migratedAt", stamp)
                        put("note", "这些 v1.1 旧记录的题目 id 在新题库里不存在，原样保留")
                        put("dropped", droppedObj)
                    }
                    File(target.parentFile, "progress.json.migrated.bak")
                        .writeText(archive.toString(2), Charsets.UTF_8)
                }
            }
        }

        // ---- 旧考试断点：题目 id 过滤后复制 ----
        val oldExam = legacyExamFile()
        if (oldExam.isFile) {
            val root = try {
                JSONObject(oldExam.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(tag, "旧 exam.json 读不出来，跳过", e)
                null
            }
            if (root != null) {
                val ids = root.optJSONArray("ids")
                val kept = JSONArray()
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        val id = ids.optString(i, "")
                        if (id in newIds) kept.put(id)
                    }
                }
                if (kept.length() > 0) {
                    root.put("ids", kept)
                    val target = examFile(targetId)
                    target.parentFile?.mkdirs()
                    target.writeText(root.toString(2), Charsets.UTF_8)
                    examMigrated = true
                }
            }
        }

        Log.i(
            tag,
            "legacy-migrate: moved=$moved dropped=$dropped " +
                "progressMigrated=$progressMigrated examMigrated=$examMigrated",
        )
        return LegacyMigrationReport(
            moved = moved,
            dropped = dropped,
            progressMigrated = progressMigrated,
            examMigrated = examMigrated,
        )
    }

    /**
     * 数旧 progress.json 的题目记录条数与 _round 长度。
     * 只统计「值是对象、且键不以 _ 开头」的条目，_round* / _current 都算内部键。
     * 文件读坏时返回 (0, 0)，不让迁移因为一个坏文件停住。
     */
    private fun countOldProgress(file: File): Pair<Int, Int> {
        if (!file.isFile) return 0 to 0
        return try {
            val rootObj = JSONObject(file.readText(Charsets.UTF_8))
            var count = 0
            var round = 0
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when {
                    key == RoundKeys.UNIFIED -> round = rootObj.optJSONArray(key)?.length() ?: 0
                    key.startsWith("_round") || key == "_current" -> Unit
                    rootObj.optJSONObject(key) != null -> count++
                }
            }
            count to round
        } catch (e: Exception) {
            Log.w(tag, "旧进度文件解析失败，条数按 0 记", e)
            0 to 0
        }
    }

    companion object {
        /** SharedPreferences 里存当前试卷 id 的键 */
        const val KEY_CURRENT_PAPER = "currentPaperId"

        /** 内置默认卷的 slug（任务书指定，不许改） */
        const val DEFAULT_PAPER_ID = "hanjie-gaoji-202610"

        /** 内置默认卷在界面上的名字 */
        const val DEFAULT_PAPER_NAME = "焊工高级 2026.10"
    }

    // ===== 11 新增：来源字段迁移与统计（只读写 meta.json / 统计文件，不碰任何算法） =====

    /** 某个源名下有几套试卷 */
    fun papersOf(sourceId: String): List<PaperMeta> = list().filter { it.sourceId == sourceId }

    /**
     * 11 的来源字段迁移（任务书第 2 条）：
     * 老版本 meta.json 没有 sourceId / origin / version，这里一次性补成
     * `origin=cloud`、`sourceId=builtin`、`version=null`（老卷的云端版本号未知）。
     *
     * 顺序与硬性要求 4 一致：**先备份 progress.json / exam.json 成 .bak-<时间戳>，再改 meta**。
     * 迁移前后各数一遍进度条数与考试题数，写进日志与返回值，验收时可直接对比。
     */
    fun migrateSourceMeta(): SourceMetaMigration {
        val dirs = root.listFiles { f -> f.isDirectory }
        if (dirs.isNullOrEmpty()) {
            return SourceMetaMigration(0, 0, 0, 0, 0, 0, emptyList(), alreadyMigrated = true)
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var paperCount = 0
        var upgraded = 0
        var progressBefore = 0
        var progressAfter = 0
        var examBefore = 0
        var examAfter = 0
        val backups = ArrayList<String>()

        for (dir in dirs) {
            val metaFile = File(dir, "meta.json")
            if (!metaFile.isFile) continue
            paperCount++
            val progress = File(dir, "progress.json")
            val exam = File(dir, "exam.json")

            // 迁移前先数一遍（老文件与已经迁移过的文件都数，保证前后可比）
            progressBefore += progressRecordCount(progress)
            examBefore += examQuestionCount(exam)

            val raw = try {
                JSONObject(metaFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(tag, "meta.json 解析失败，跳过来源迁移：${metaFile.absolutePath}", e)
                continue
            }

            if (raw.has("origin") && raw.has("sourceId")) {
                // 已经迁移过：不重复备份、不重复改写，只把条数带进对账
                progressAfter += progressRecordCount(progress)
                examAfter += examQuestionCount(exam)
                continue
            }

            // ---- 第 1 步：备份（进度与考试状态，复制，不移动）----
            if (progress.isFile) {
                val bak = File(dir, "progress.json.bak-$stamp")
                runCatching { progress.copyTo(bak, overwrite = true) }
                    .onSuccess { backups.add(bak.absolutePath) }
                    .onFailure { Log.w(tag, "备份 progress.json 失败：${progress.absolutePath}", it) }
            }
            if (exam.isFile) {
                val bak = File(dir, "exam.json.bak-$stamp")
                runCatching { exam.copyTo(bak, overwrite = true) }
                    .onSuccess { backups.add(bak.absolutePath) }
                    .onFailure { Log.w(tag, "备份 exam.json 失败：${exam.absolutePath}", it) }
            }

            // ---- 第 2 步：补三个字段（version 写 JSON null，表示云端版本未知）----
            raw.put("origin", PaperMeta.ORIGIN_CLOUD)
            raw.put("sourceId", SourceConfig.BUILTIN_ID)
            if (!raw.has("version")) raw.put("version", JSONObject.NULL)
            try {
                metaFile.writeText(raw.toString(2), Charsets.UTF_8)
                upgraded++
                Log.i(tag, "来源迁移：${dir.name} -> origin=cloud sourceId=builtin")
            } catch (e: Exception) {
                Log.e(tag, "来源迁移写 meta 失败：${metaFile.absolutePath}", e)
            }

            // ---- 第 3 步：迁移后再数一遍 ----
            progressAfter += progressRecordCount(progress)
            examAfter += examQuestionCount(exam)
        }

        val report = SourceMetaMigration(
            paperCount = paperCount,
            upgraded = upgraded,
            progressBefore = progressBefore,
            progressAfter = progressAfter,
            examBefore = examBefore,
            examAfter = examAfter,
            backups = backups,
            alreadyMigrated = upgraded == 0,
        )
        Log.i(
            tag,
            "来源迁移完成：试卷=$paperCount，升级=$upgraded，" +
                "progress=$progressBefore->$progressAfter，exam=$examBefore->$examAfter，" +
                "备份=${backups.size} 个",
        )
        return report
    }

    /** 进度文件里的题目记录条数（不含 _round* / _current 这些内部键）；坏文件按 0 */
    fun progressRecordCount(file: File): Int {
        val root = readJson(file) ?: return 0
        var count = 0
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("_")) continue
            if (root.optJSONObject(key) != null) count++
        }
        return count
    }

    /** 某个轮次本轮已出题条数；没有该轮次返回 0 */
    fun progressRoundCount(file: File, key: String): Int {
        val root = readJson(file) ?: return 0
        return root.optJSONArray(key)?.length() ?: 0
    }

    /** 一句进度概要（调试模式导出诊断用） */
    fun progressSummaryText(file: File): String {
        if (!file.isFile) return "无 progress.json"
        val root = readJson(file) ?: return "progress.json 读取失败"
        val parts = ArrayList<String>()
        parts.add("records=${progressRecordCount(file)}")
        for (key in RoundKeys.ALL) {
            parts.add("$key=${root.optJSONArray(key)?.length() ?: 0}")
        }
        parts.add("current=${if (root.isNull("_current")) "-" else root.optString("_current", "-")}")
        return parts.joinToString(", ")
    }

    /** exam.json 里的题目数（= ids 数组长度）；没有或读坏按 0 */
    fun examQuestionCount(file: File): Int {
        val root = readJson(file) ?: return 0
        return root.optJSONArray("ids")?.length() ?: 0
    }

    /** 目录里直接子文件数（诊断里的图片张数） */
    fun fileCount(dir: File): Int = dir.listFiles { f -> f.isFile }?.size ?: 0

    /** 目录总字节数（诊断用） */
    fun dirSizeBytes(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { file -> if (file.isFile) total += file.length() }
        return total
    }

    private fun readJson(file: File): JSONObject? {
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(tag, "JSON 读取失败：${file.absolutePath}", e)
            null
        }
    }
}

/**
 * 来源迁移报告。
 * @param progressBefore / @param progressAfter 迁移前 / 后的进度记录总条数（必须相等）
 * @param examBefore / @param examAfter 迁移前 / 后的考试题数（必须相等）
 * @param backups 备份文件路径
 */
data class SourceMetaMigration(
    val paperCount: Int,
    val upgraded: Int,
    val progressBefore: Int,
    val progressAfter: Int,
    val examBefore: Int,
    val examAfter: Int,
    val backups: List<String>,
    val alreadyMigrated: Boolean,
)

/**
 * v1.3：v1.1 旧数据（filesDir 根目录）搬进新试卷目录的结果。
 * @param moved 按题目 id 成功搬过去的记录条数
 * @param dropped 题目 id 在新题库里不存在、已留档到 progress.json.migrated.bak 的条数
 */
data class LegacyMigrationReport(
    val moved: Int,
    val dropped: Int,
    val progressMigrated: Boolean,
    val examMigrated: Boolean,
)
