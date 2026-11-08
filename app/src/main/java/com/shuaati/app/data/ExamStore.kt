// 数据层：把「正在进行的模拟考试」存到 filesDir/exam.json。
// 目的只有一个：考试中退出 App（或进程被杀）后，重新进来还能接着考。
// 文件里存的是题目 id 列表 + 每题已选答案 + 当前题号 + 开始/结束时间戳，
// 不存题目正文（题目永远以 assets/questions.json 为准）。
package com.shuaati.app.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一份存到磁盘的考试进度。
 * @param ids 试卷题目 id，顺序就是考生的题号顺序
 * @param answers 已作答的题：题目 id → 选中的选项 key 集合（未作答的题不在里面）
 * @param index 当前停在第几题（从 0 开始）
 * @param startAt 开考时间戳（毫秒）
 * @param endAt 结束时间戳（毫秒）——倒计时的唯一依据，算剩余时间永远是「endAt − 现在」
 * @param savedRemainingSec 写盘那一刻的剩余秒数（给人工核对文件用；恢复时仍按 endAt 重算）
 */
data class SavedExam(
    val ids: List<String>,
    val answers: Map<String, Set<String>>,
    val index: Int,
    val startAt: Long,
    val endAt: Long,
    val durationSec: Int,
    val savedRemainingSec: Int,
) {
    /** 按「结束时间戳 − 当前时间」算剩余秒数；已经过点返回 0 */
    fun remainingSecNow(now: Long = System.currentTimeMillis()): Int =
        (((endAt - now).coerceAtLeast(0L)) / 1000L).toInt()

    /** 是否已经超时（超时的考试不再恢复） */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now >= endAt
}

/**
 * exam.json 的读写器。**一个实例对应一套试卷的一个文件**（06B 起考试状态也按试卷隔离），
 * 切试卷时由 PaperSession 重新构造。
 *
 * 读坏了、题目 id 全是空的、已经超时，都按「没有未完成的考试」处理，并顺手把坏文件删掉，
 * 绝不让一个坏文件把 App 卡在启动弹窗上。
 *
 * @param file 考试断点文件（App 里永远指向「当前试卷」目录下的 exam.json）
 */
class ExamStore(private val file: File) {

    /** 便利构造：不关心试卷、只想用默认位置时用（旧调用点与测试都还能用） */
    constructor(context: Context) : this(File(context.filesDir, "exam.json"))

    private val tag = "Shuaati"

    /** 文件绝对路径，验收时打印用 */
    val filePath: String get() = file.absolutePath

    /** 文件是否还在（只判断存在，不判断内容是否有效） */
    fun exists(): Boolean = file.exists()

    /**
     * 读取一份「还没考完、也没超时」的考试。
     * 返回 null 表示没有可恢复的考试（文件不存在 / 内容损坏 / 已经超时）。
     */
    fun loadActive(now: Long = System.currentTimeMillis()): SavedExam? {
        if (!file.exists()) return null
        val exam = try {
            parse(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(tag, "exam.json 读取失败，按没有考试处理", e)
            null
        }
        if (exam == null) {
            Log.w(tag, "exam.json 内容无效，已删除：${file.absolutePath}")
            clear()
            return null
        }
        if (exam.isExpired(now)) {
            // 超时的考试不恢复：时间已经用完，恢复出来也只能立刻自动交卷，不如直接丢弃
            Log.i(tag, "exam.json 已超时，丢弃：endAt=${exam.endAt} now=$now")
            clear()
            return null
        }
        Log.i(
            tag,
            "exam.json 可恢复：题数=${exam.ids.size}，已答=${exam.answers.size}，" +
                "第 ${exam.index + 1} 题，剩余=${exam.remainingSecNow(now)} 秒",
        )
        return exam
    }

    /**
     * 保存考试进度。每次点选项、翻页、跳题都会调用一次，
     * 这样进程随时被杀，最坏也只丢最后一次点击。
     */
    fun save(exam: SavedExam) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("ids", JSONArray(exam.ids))

        // 答案存成「题目 id → 排序后的选项字母」，例如 "M001": "ABD"，人也能直接看
        val answers = JSONObject()
        exam.answers.forEach { (id, keys) ->
            if (keys.isNotEmpty()) answers.put(id, keys.sorted().joinToString(""))
        }
        root.put("answers", answers)
        root.put("index", exam.index)
        root.put("startAt", exam.startAt)
        root.put("endAt", exam.endAt)
        root.put("durationSec", exam.durationSec)
        root.put("remainingSec", exam.remainingSecNow())
        root.put("savedAt", System.currentTimeMillis())

        val text = root.toString(2)
        val tmp = File(file.parentFile, "exam.json.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 个别文件系统不支持覆盖式改名，退回直接写
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(tag, "exam.json 写盘失败", e)
        }
    }

    /** 删除考试进度：交卷、放弃考试、发现坏文件时都用它 */
    fun clear() {
        try {
            if (file.exists() && !file.delete()) Log.w(tag, "exam.json 删除失败")
            // 顺便清理可能残留的临时文件
            File(file.parentFile, "exam.json.tmp").takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            Log.e(tag, "exam.json 删除异常", e)
        }
    }

    /** 解析 JSON；字段缺失或明显不对时返回 null，由调用方决定丢弃 */
    private fun parse(text: String): SavedExam? {
        val root = JSONObject(text)

        val arr = root.optJSONArray("ids") ?: return null
        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val id = arr.optString(i, "")
            if (id.isNotBlank()) ids.add(id)
        }
        if (ids.isEmpty()) return null

        val answers = LinkedHashMap<String, Set<String>>()
        val obj = root.optJSONObject("answers")
        if (obj != null) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                // "ABD" → {A, B, D}；非法字符直接忽略
                val set = obj.optString(id, "")
                    .filter { it in 'A'..'Z' || it in 'a'..'z' }
                    .map { it.uppercaseChar().toString() }
                    .toSet()
                if (set.isNotEmpty()) answers[id] = set
            }
        }

        val endAt = root.optLong("endAt", 0L)
        if (endAt <= 0L) return null

        return SavedExam(
            ids = ids,
            answers = answers,
            index = root.optInt("index", 0),
            startAt = root.optLong("startAt", 0L),
            endAt = endAt,
            durationSec = root.optInt("durationSec", ExamBuilder.DURATION_SECONDS),
            savedRemainingSec = root.optInt("remainingSec", 0),
        )
    }
}
