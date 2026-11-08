// 数据层（06B 新增）：导入一套试卷的校验与落盘。
//
// 本地文件导入、WebDAV 下载、将来可能的其它来源，全部走这里同一套校验
// （硬性要求 3）：只要有一条不满足就拒绝，并说清是「第几题、哪里不对」——
// 绝对不许「有 questions 数组就收」。
//
// 校验四条（任务书第 3 条）：
//   a) 顶层有 questions 数组且长度 >= 1
//   b) 每题有 id/type/stem/options，type ∈ {single, judge, multi}
//   c) 单选 4 个选项、多选 >= 2 个选项、判断固定 T/F
//   d) id 在本次导入内不重复
package com.shuaati.app.data

import android.util.Log
import org.json.JSONObject

/** 一套试卷的导入器 */
object PaperImporter {

    private const val TAG = "Shuaati"

    /** 允许的题型 */
    private val ALLOWED_TYPES = setOf("single", "judge", "multi")

    /** 校验结果：要么拿到可用的题目列表，要么拿到一句给人看的中文原因 */
    sealed class Validation {
        /**
         * @param questions 解析好的题目
         * @param title 文件里 meta.title（没有就是 null，落盘时用文件名当试卷名）
         * @param warnings 不阻塞导入、但值得提示的问题（例如 N 道题没有答案）
         */
        data class Ok(
            val questions: List<Question>,
            val title: String?,
            val warnings: List<String>,
        ) : Validation()

        /** @param reason 具体到「第几题哪里不对」的中文原因 */
        data class Fail(val reason: String) : Validation()
    }

    /** 落盘结果 */
    data class ImportResult(
        val meta: PaperMeta?,
        val reason: String?,
        val warnings: List<String>,
    ) {
        val ok: Boolean get() = meta != null
    }

    /**
     * 只做校验，不写任何文件。网络下载时先用它拦一遍，避免把坏文件写进试卷库。
     */
    fun parseAndValidate(text: String): Validation {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Validation.Fail("不是合法的 JSON 文件：${e.message ?: "解析失败"}")
        }

        // ---- a) 顶层 questions 数组且长度 >= 1 ----
        val array = root.optJSONArray("questions")
            ?: return Validation.Fail("顶层缺少 questions 数组（文件里找不到题目列表）")
        if (array.length() < 1) {
            return Validation.Fail("questions 数组是空的，至少要有 1 道题")
        }

        val seenIds = HashSet<String>()
        val questions = ArrayList<Question>(array.length())
        // 不阻塞导入的提示先收集起来，最后汇总成一句话
        var noAnswerCount = 0
        var badAnswerCount = 0
        var firstBadAnswer = ""

        for (i in 0 until array.length()) {
            val position = i + 1
            val obj = array.optJSONObject(i)
                ?: return Validation.Fail("第 $position 题不是 JSON 对象（questions 里混入了别的类型）")

            // ---- b) id / type / stem / options ----
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) return Validation.Fail("第 $position 题缺少 id")
            val type = obj.optString("type", "").trim()
            if (type !in ALLOWED_TYPES) {
                val shown = if (type.isEmpty()) "缺少 type" else "「$type」"
                return Validation.Fail(
                    "第 $position 题的 type 不对：$shown；只能是 single（单选）/ judge（判断）/ multi（多选）",
                )
            }
            val stem = obj.optString("stem", "").trim()
            if (stem.isEmpty()) return Validation.Fail("第 $position 题缺少题干 stem")
            if (obj.isNull("options")) {
                return Validation.Fail("第 $position 题缺少 options 数组")
            }
            val optionsArray = obj.optJSONArray("options")
                ?: return Validation.Fail("第 $position 题的 options 不是数组")

            val question = try {
                QuestionRepo.parseQuestion(obj)
            } catch (e: Exception) {
                return Validation.Fail("第 $position 题的选项格式不对：${e.message ?: "无法解析"}")
            }
            val options = question.options
            if (options.isEmpty()) return Validation.Fail("第 $position 题的 options 是空数组")
            if (options.any { it.key.isBlank() }) {
                return Validation.Fail("第 $position 题有选项缺少 key（A/B/C/D 或 T/F）")
            }
            if (options.map { it.key }.distinct().size != options.size) {
                return Validation.Fail("第 $position 题的选项 key 有重复：${options.joinToString("/") { it.key }}")
            }

            // ---- c) 各题型的选项个数 ----
            when (type) {
                "single" -> if (optionsArray.length() != 4) {
                    return Validation.Fail(
                        "第 $position 题选项数不对：单选题要 4 个，实际只有 ${optionsArray.length()} 个",
                    )
                }

                "multi" -> if (optionsArray.length() < 2) {
                    return Validation.Fail(
                        "第 $position 题选项数不对：多选题至少 2 个，实际只有 ${optionsArray.length()} 个",
                    )
                }

                "judge" -> {
                    val keys = options.map { it.key }.toSet()
                    if (optionsArray.length() != 2 || keys != setOf("T", "F")) {
                        return Validation.Fail(
                            "第 $position 题判断题选项不是固定的 T/F：实际是 " +
                                options.joinToString("/") { it.key },
                        )
                    }
                }
            }

            // ---- d) id 在本次导入内不重复 ----
            if (!seenIds.add(id)) {
                return Validation.Fail("第 $position 题的 id「$id」与前面的题重复了")
            }

            // 附加提示（不拒绝）：答案为空或答案字母不在选项里，这些题只能「看题不判分」
            val answer = obj.optString("answer", "").trim()
            if (answer.isEmpty()) {
                noAnswerCount++
            } else if (question.correctKeys.isEmpty()) {
                badAnswerCount++
                if (firstBadAnswer.isEmpty()) {
                    firstBadAnswer = "第 $position 题的答案「$answer」不在选项里"
                }
            }

            questions.add(question)
        }

        val warnings = ArrayList<String>(3)
        if (noAnswerCount > 0) warnings.add("有 $noAnswerCount 道题没有答案，刷到时只显示题干、不判对错")
        if (badAnswerCount > 0) warnings.add("$firstBadAnswer${if (badAnswerCount > 1) "等 $badAnswerCount 道题" else ""}")

        val title = root.optJSONObject("meta")?.optString("title", "")?.trim()?.takeIf { it.isNotEmpty() }
        return Validation.Ok(questions = questions, title = title, warnings = warnings)
    }

    /**
     * 校验 + 落盘成一套新试卷。
     *
     * 落盘位置：papers\<新 id>\questions.json + meta.json（进度从空开始：不写 progress.json）。
     * 写完会**再读回一遍**，确认文件真的能解析，避免「写进去了但读不出来」。
     *
     * @param repo 试卷库
     * @param text 题库 JSON 原文
     * @param source 「本地导入」/「网络下载」
     * @param nameHint 没有 meta.title 时用这个名字（一般是文件名）
     * @param origin `cloud` / `local`（11 新增：决定列表里显示源名还是「本地」标签）
     * @param sourceId 来自哪个源；本地导入传 null
     * @param version 云端版本号；本地导入传 null
     * @param remoteId 源上的 paperId；本地导入传 null（v1.3 更新检测按它对齐）
     * @param targetId v1.3：指定试卷目录 id（从源下载时用源上的 paperId，
     *                 这样「同一 paperId」天然成立）。为 null 时自动生成 import-时间戳。
     */
    fun importTo(
        repo: PaperRepo,
        text: String,
        source: String,
        nameHint: String?,
        origin: String = PaperMeta.ORIGIN_CLOUD,
        sourceId: String? = null,
        version: Int? = null,
        remoteId: String? = null,
        targetId: String? = null,
    ): ImportResult {
        val validated = parseAndValidate(text)
        if (validated is Validation.Fail) {
            return ImportResult(meta = null, reason = validated.reason, warnings = emptyList())
        }
        val ok = validated as Validation.Ok

        val id = targetId?.trim()?.takeIf { it.isNotEmpty() } ?: repo.newPaperId()
        val dir = repo.dirOf(id)
        if (!dir.mkdirs() && !dir.isDirectory) {
            return ImportResult(null, "无法创建试卷目录：${dir.absolutePath}", emptyList())
        }

        val questionsFile = repo.questionsFile(id)
        try {
            questionsFile.writeText(text, Charsets.UTF_8)
            // 写完立刻读回：文件被截断、编码坏了都能在这里发现，而不是等用户点开试卷才崩
            val reread = QuestionRepo.loadFromFile(questionsFile)
            val name = ok.title?.takeIf { it.isNotBlank() }
                ?: nameHint?.trim()?.takeIf { it.isNotBlank() }
                ?: "导入的试卷"
            val meta = PaperMeta.fromQuestions(
                id = id,
                name = name,
                questions = reread,
                source = source,
                importedAt = repo.nowText(),
                sourceId = sourceId,
                origin = origin,
                version = version,
                remoteId = remoteId,
            )
            repo.writeMeta(meta)
            Log.i(
                TAG,
                "导入成功：$id「$name」共 ${reread.size} 题，来源=$source，" +
                    "origin=$origin sourceId=${sourceId ?: "null"} " +
                    "remoteId=${remoteId ?: "null"} version=${version ?: "null"}",
            )
            return ImportResult(meta = meta, reason = null, warnings = ok.warnings)
        } catch (e: Exception) {
            // 半成品目录要清掉，否则它会一直留在 papers 里占地方
            runCatching { dir.deleteRecursively() }
            Log.e(TAG, "导入落盘失败：${dir.absolutePath}", e)
            return ImportResult(null, "写入试卷失败：${e.message ?: "未知错误"}", emptyList())
        }
    }
}
