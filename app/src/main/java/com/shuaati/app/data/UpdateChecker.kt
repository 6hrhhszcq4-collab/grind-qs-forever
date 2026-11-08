// 数据层（v1.3 重写）：试卷更新检测。
//
// 为什么整个重写：v1.2 的检查逻辑有两处硬伤（已在模拟器上复现，见验收报告）：
//   1. 只有 meta.version != null 的本地卷才参与比较。v1.1 内置题库迁移出来的卷
//      version 是 JSON null，于是被整批过滤，日志是「0 套本地云端卷」，永远不提示；
//   2. 匹配本地卷用 id 或 name 相等，但下载时 id 是 import-时间戳、名字取的是题库 JSON
//      里的 meta.title（「焊工 高级2026.10」），和 papers.json 的 name（「焊工高级 2026.10」）
//      差一个空格，两边都对不上，同样永远不提示。
//
// v1.3 的规则（任务书第 2 条）：
//   1. 版本文件位置按源算：`<该源 baseUrl><该源 path>version.json`，默认源与自建源同一段代码；
//   2. 三级读取（缺一级退一级，绝不弹错误框）：
//        ① GET version.json → {paperId, version, updatedAt}
//        ② 404 → GET papers.json → papers[] 里找同一个 paperId 的 version
//        ③ 都没有 → 该源「不支持自动检测」，记日志后静默跳过；
//   3. 本地按 paperId 对齐（meta.remoteId / meta.id），版本号 null 视为「版本未知」，
//      远端有版本就提示，不再被过滤掉；
//   4. 用户点「下载」才更新：下载 → 校验 → **先迁移进度** → 再换题库 → 写 meta.version；
//   5. 网络失败一律静默，只打日志。
//
// 日志格式（任务书规定，不许改）：
//   update-check: local=<v> remote=<v> action=<skip|prompt|download>
//   update-check: failed <原因>
package com.shuaati.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 从公网拿到的版本信息。
 * @param paperId 源上的试卷编号（与本地 meta.remoteId / meta.id 对齐）
 * @param version 版本号
 * @param updatedAt 版本日期（version.json 才有；从 papers.json 回退时为 null）
 * @param fromVersionFile true = 来自 version.json；false = 来自 papers.json
 */
data class RemoteVersion(
    val paperId: String,
    val version: Int,
    val updatedAt: String?,
    val fromVersionFile: Boolean,
)

/**
 * 一条「可以更新」的提示。
 * @param localMeta 本地要原地更新的那套试卷（progress.json / exam.json 都不动）
 */
data class PaperUpdate(
    val source: SourceConfig,
    val paperId: String,
    val remoteVersion: Int,
    val updatedAt: String?,
    val localMeta: PaperMeta,
)

/** 一次检查的结果（可能跨多个源） */
data class UpdateCheckOutcome(
    /** 需要弹窗询问的更新（已排除本次启动点过「以后再说」的） */
    val updates: List<PaperUpdate>,
    /** 明确「不支持自动检测」的源名（用户仍可在源管理页手动刷新） */
    val unsupportedSources: List<String>,
    /** 网络失败、已静默跳过的源名 */
    val failedSources: List<String>,
)

/** 一次更新做完的结果，给界面拼提示文案用 */
data class UpdateApplyResult(
    val update: PaperUpdate,
    val total: Int,
    val migration: ProgressMigrationReport,
    val images: ImageDownloadResult,
)

/** 启动/手动更新检查器 */
object UpdateChecker {

    private const val TAG = "Shuaati"

    /**
     * 检查全部源。**调用方负责在 IO 线程调用**（内部也切了 IO，重复切无害）。
     *
     * @param repo 试卷库（本地老卷补 remoteId 时要写 meta）
     * @param sources 全部题库源
     * @param localPapers 本地全部试卷
     * @param skipped 本次启动已经点过「以后再说」的：paperId -> 版本号
     */
    suspend fun checkAll(
        repo: PaperRepo,
        sources: List<SourceConfig>,
        localPapers: List<PaperMeta>,
        skipped: Map<String, Int> = emptyMap(),
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val updates = ArrayList<PaperUpdate>()
        val unsupported = ArrayList<String>()
        val failed = ArrayList<String>()
        for (source in sources) {
            // 网络失败只能静默：每个源单独 try/catch，一个源出错不影响别的源
            try {
                val result = checkOneSource(repo, source, localPapers, skipped)
                updates.addAll(result.updates)
                unsupported.addAll(result.unsupportedSources)
                failed.addAll(result.failedSources)
            } catch (e: Exception) {
                Log.w(TAG, "update-check: failed 检查源「${source.name}」时出现未预期错误：${e.message}")
                failed.add(source.name)
            }
        }
        Log.i(
            TAG,
            "update-check: 汇总 源=${sources.size} 可更新=${updates.size} " +
                "不支持自动检测=${unsupported.size} 失败=${failed.size}",
        )
        UpdateCheckOutcome(updates, unsupported, failed)
    }

    /** 检查单个源 */
    private suspend fun checkOneSource(
        repo: PaperRepo,
        source: SourceConfig,
        localPapers: List<PaperMeta>,
        skipped: Map<String, Int>,
    ): UpdateCheckOutcome {
        // 只检查「来自这个源、且来自云端」的本地卷；本地导入的卷没有远端对应物
        val candidates = localPapers.filter {
            it.origin == PaperMeta.ORIGIN_CLOUD && it.sourceId == source.id
        }
        if (candidates.isEmpty()) {
            Log.i(TAG, "update-check: 源「${source.name}」没有本地的云端试卷，跳过")
            return UpdateCheckOutcome(emptyList(), emptyList(), emptyList())
        }

        // ===== 第一级：GET version.json =====
        val remoteVersions = ArrayList<RemoteVersion>()
        var versionFileUsable = false
        try {
            val text = WebDavSource.fetchVersionText(source)
            if (text == null) {
                // 404：不是错误，静默退到第二级
                Log.i(TAG, "update-check: 源「${source.name}」没有版本文件（HTTP 404），改读索引")
            } else {
                val parsed = parseVersionFile(text)
                if (parsed == null) {
                    Log.w(TAG, "update-check: failed 版本文件格式不对（源=${source.name}），改读索引")
                } else {
                    remoteVersions.add(parsed)
                    versionFileUsable = true
                    Log.i(
                        TAG,
                        "update-check: 源「${source.name}」版本文件可读 paperId=${parsed.paperId} " +
                            "version=${parsed.version} updatedAt=${parsed.updatedAt ?: "-"}",
                    )
                }
            }
        } catch (e: Exception) {
            // 网络失败：静默，不弹窗
            Log.w(TAG, "update-check: failed 读取版本文件失败（源=${source.name}）：${e.message}")
            return UpdateCheckOutcome(emptyList(), emptyList(), listOf(source.name))
        }

        // ===== 第二级：GET papers.json（只在第一级拿不到时） =====
        var remoteNameOf = HashMap<String, String>()
        if (!versionFileUsable) {
            val index = try {
                WebDavSource.loadIndex(source)
            } catch (e: Exception) {
                Log.w(TAG, "update-check: failed 读取索引失败（源=${source.name}）：${e.message}")
                return UpdateCheckOutcome(emptyList(), emptyList(), listOf(source.name))
            }
            if (!index.fileExists) {
                // 第三级：两者都拿不到 → 该源不支持自动检测
                Log.i(
                    TAG,
                    "update-check: 源「${source.name}」既没有版本文件也没有索引，标记为不支持自动检测，静默跳过",
                )
                return UpdateCheckOutcome(emptyList(), listOf(source.name), emptyList())
            }
            if (index.entries.isEmpty()) {
                Log.i(TAG, "update-check: 源「${source.name}」索引里没有试卷，静默跳过")
                return UpdateCheckOutcome(emptyList(), emptyList(), emptyList())
            }
            for (entry in index.entries) {
                remoteVersions.add(
                    RemoteVersion(
                        paperId = entry.id,
                        version = entry.version,
                        updatedAt = null,
                        fromVersionFile = false,
                    )
                )
                // 记下名字，用来给「老下载卷」补 remoteId（只补一次，之后都按 paperId 匹配）
                remoteNameOf[entry.id] = entry.name
            }
        }

        // ===== 兼容 v1.2 下载卷：补一张「paperId -> 名字」表 =====
        // v1.2 下载的卷没有 remoteId、id 又是 import-时间戳，只能靠名称认领。
        // 如果版本来源是 version.json（它没有名字字段），这里额外读一次索引把名字补上；
        // 读不到就只记日志，不影响版本比较（最坏情况是老卷这次不提示，不会误报）。
        if (remoteNameOf.isEmpty() && candidates.any { it.remoteId.isNullOrBlank() }) {
            try {
                WebDavSource.loadIndex(source).entries.forEach { remoteNameOf[it.id] = it.name }
                Log.i(
                    TAG,
                    "update-check: 本地有 v1.2 下载卷（无 paperId），已读索引补名称表 ${remoteNameOf.size} 条",
                )
            } catch (e: Exception) {
                Log.w(TAG, "update-check: failed 读索引补名称表失败：${e.message}")
            }
        }

        // ===== 逐条比对 =====
        val updates = ArrayList<PaperUpdate>()
        for (remote in remoteVersions) {
            // 本地对齐：先按 remoteId / id 精确匹配；都对不上再按规范化名称匹配一次（兼容 v1.2 下载卷）
            var local = candidates.firstOrNull { it.remoteId == remote.paperId || it.id == remote.paperId }
            if (local == null) {
                val remoteName = remoteNameOf[remote.paperId]
                if (remoteName != null) {
                    local = candidates.firstOrNull {
                        normalizedName(it.name) == normalizedName(remoteName)
                    }
                    if (local != null) {
                        // 补 remoteId：这是最后一次按名字匹配，之后这套卷都按 paperId 对齐
                        try {
                            repo.writeMeta(local.copy(remoteId = remote.paperId))
                            Log.i(
                                TAG,
                                "update-check: 老卷「${local.name}」补上 paperId=${remote.paperId}（按名称匹配）",
                            )
                            local = local.copy(remoteId = remote.paperId)
                        } catch (e: Exception) {
                            Log.w(TAG, "update-check: failed 写回 paperId 失败：${e.message}")
                        }
                    }
                }
            }
            if (local == null) {
                // 本地没有这个 paperId → 当作「未下载」，不弹更新（下载入口在源管理页/首页）
                Log.i(
                    TAG,
                    "update-check: local=未下载 remote=${remote.version} action=skip " +
                        "（paperId=${remote.paperId}，源=${source.name}）",
                )
                continue
            }

            val localVersion = local.version
            // 本次启动点过「以后再说」的版本，不再追问
            if (skipped[remote.paperId] == remote.version) {
                Log.i(
                    TAG,
                    "update-check: local=${localVersion ?: "null"} remote=${remote.version} action=skip（本次启动已跳过）",
                )
                continue
            }

            // 本地版本未知（null）时按「需要更新」处理：老师从 v1.1 升上来的卷就是这种情况，
            // 不提示的话就永远拿不到新题库。
            val needUpdate = localVersion == null || remote.version > localVersion
            Log.i(
                TAG,
                "update-check: local=${localVersion ?: "null"} remote=${remote.version} " +
                    "action=${if (needUpdate) "prompt" else "skip"}",
            )
            if (needUpdate) {
                updates.add(
                    PaperUpdate(
                        source = source,
                        paperId = remote.paperId,
                        remoteVersion = remote.version,
                        updatedAt = remote.updatedAt,
                        localMeta = local,
                    )
                )
            }
        }
        return UpdateCheckOutcome(updates, emptyList(), emptyList())
    }

    /**
     * 用户点「下载」后真正更新一套试卷。
     *
     * 顺序（硬性要求 4 与任务书第 3 条）：
     *   1. 读索引拿 file → 下载题库文本；
     *   2. 先校验（解析、questions>0、每题 id/type/stem/options），不通过就直接失败、不写盘；
     *   3. 读旧题库，算进度映射并**先把 progress.json 迁移好**（迁移器内部会先备份）；
     *   4. 再覆盖 questions.json；写失败就回滚进度；
     *   5. 刷新 meta.version / remoteId，最后补图片。
     *
     * @throws WebDavException 下载、校验、写盘失败时抛出（中文原因）
     */
    suspend fun applyUpdate(repo: PaperRepo, update: PaperUpdate): UpdateApplyResult =
        withContext(Dispatchers.IO) {
            val source = update.source
            val meta = update.localMeta
            val what = "更新「${meta.name}」"

            // ---- 1. 从索引里找到这套卷的 file 字段（任务书：先 GET papers.json 拿 file）----
            val index = WebDavSource.loadIndex(source)
            if (!index.fileExists) {
                throw WebDavException("$what 失败：题库源上没有索引文件，无法确认要下载哪个文件")
            }
            val entry = index.entries.firstOrNull { it.id == update.paperId }
                ?: throw WebDavException("$what 失败：源上的索引里找不到这套试卷（${update.paperId}）")

            // ---- 2. 下载 + 校验：校验不过绝不写盘 ----
            val text = WebDavSource.fetchQuestions(source, entry)
            val validated = PaperImporter.parseAndValidate(text)
            if (validated is PaperImporter.Validation.Fail) {
                throw WebDavException("$what 失败：下载的题库校验不通过（${validated.reason}）")
            }
            val newQuestions = (validated as PaperImporter.Validation.Ok).questions

            val questionsFile = repo.questionsFile(meta.id)
            val parentDir = questionsFile.parentFile
            if (parentDir == null || !parentDir.isDirectory) {
                throw WebDavException("$what 失败：本地试卷目录已不存在")
            }

            // ---- 3. 读旧题库（读不到就按空处理：进度会全部留档到 .migrated.bak，不会丢）----
            val oldQuestions = try {
                QuestionRepo.loadFromFile(questionsFile)
            } catch (e: Exception) {
                Log.w(TAG, "update-check: 旧题库读不出来，进度将按无法匹配留档", e)
                emptyList()
            }

            // ---- 4. 进度迁移：必须在写新 questions.json 之前完成 ----
            val migration = ProgressMigrator.migrate(
                progressFile = repo.progressFile(meta.id),
                oldQuestions = oldQuestions,
                newQuestions = newQuestions,
                timestamp = nowStamp(),
            )

            // ---- 5. 覆盖题库；失败回滚进度 ----
            val stamp = nowStamp()
            if (questionsFile.isFile) {
                runCatching {
                    questionsFile.copyTo(File(parentDir, "questions.json.bak-$stamp"), overwrite = true)
                }.onFailure { Log.w(TAG, "更新前备份题库失败：${questionsFile.absolutePath}", it) }
            }
            try {
                questionsFile.writeText(text, Charsets.UTF_8)
                // 写完读回一遍，避免「写进去了但读不出来」
                val reread = QuestionRepo.loadFromFile(questionsFile)
                if (reread.isEmpty()) {
                    throw WebDavException("$what 失败：写入后读不回来，已保留备份")
                }

                // ---- 6. 刷新 meta：版本号写远端值；名字保持不变（避免界面上的试卷名忽然改了）----
                val fresh = meta.copy(
                    total = reread.size,
                    singleCount = reread.count { it.type == "single" },
                    judgeCount = reread.count { it.type == "judge" },
                    multiCount = reread.count { it.type == "multi" },
                    version = update.remoteVersion,
                    remoteId = update.paperId,
                )
                repo.writeMeta(fresh)
            } catch (e: Exception) {
                // 题库写失败：把进度恢复到迁移前，保证「要么整套更新成功，要么保持原样」
                migration.backupPath?.let { backup ->
                    runCatching {
                        File(backup).copyTo(repo.progressFile(meta.id), overwrite = true)
                        Log.i(TAG, "update-check: 题库写入失败，进度已回滚到迁移前")
                    }.onFailure { Log.e(TAG, "update-check: 进度回滚失败，备份仍在：$backup", it) }
                }
                if (e is WebDavException) throw e
                throw WebDavException("$what 失败：写入题库出错（${e.message ?: "未知错误"}）")
            }

            // ---- 7. 图片：失败不影响题目可用，只记日志 ----
            val imageResult = try {
                WebDavSource.downloadImages(source, text, repo.imagesDir(meta.id))
            } catch (e: Exception) {
                Log.w(TAG, "update-check: 更新时下载图片整体失败", e)
                ImageDownloadResult(0, 0, emptyList())
            }

            // 任务书要求的那一行「下载完成」日志
            Log.i(
                TAG,
                "update-check: local=${meta.version ?: "null"} remote=${update.remoteVersion} action=download",
            )
            Log.i(
                TAG,
                "更新成功：${meta.id}「${meta.name}」v${meta.version ?: "-"} -> v${update.remoteVersion}，" +
                    "题数=${newQuestions.size}，${migration.logLine()}，" +
                    "图片成功 ${imageResult.total - imageResult.failed}/${imageResult.total}",
            )
            UpdateApplyResult(
                update = update,
                total = newQuestions.size,
                migration = migration,
                images = imageResult,
            )
        }

    /**
     * 手动检查更新（设置页「检查更新」按钮用）：与启动检查完全同一段代码，
     * 区别只是调用方自己决定弹不弹对话框。
     */
    suspend fun manualCheck(
        repo: PaperRepo,
        sources: List<SourceConfig>,
        localPapers: List<PaperMeta>,
    ): UpdateCheckOutcome = checkAll(repo, sources, localPapers, skipped = emptyMap())

    // ===== 内部工具 =====

    /** 解析 version.json；格式不对返回 null（调用方退到 papers.json） */
    private fun parseVersionFile(text: String): RemoteVersion? {
        return try {
            val root = JSONObject(text)
            val paperId = root.optString("paperId", "").trim()
            if (paperId.isEmpty()) return null
            RemoteVersion(
                paperId = paperId,
                version = root.optInt("version", 0),
                updatedAt = root.optString("updatedAt", "").trim().takeIf { it.isNotEmpty() },
                fromVersionFile = true,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 名称规范化：去掉空白与常见分隔符，用来兼容 v1.2 下载卷
     * （题库 JSON 的 meta.title 是「焊工 高级2026.10」，papers.json 是「焊工高级 2026.10」）。
     * 只用于「补一次 remoteId」，补完之后一律按 paperId 对齐。
     */
    private fun normalizedName(name: String): String =
        name.replace(Regex("[\\s·•\\-—_.、，,（）()【】\\[\\]]"), "").trim()

    /** 备份文件名用的时间戳 */
    private fun nowStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
