// 数据层：题目与选项的数据结构，字段名与 assets/questions.json 完全一致
package com.shuaati.app.data

/** 一个选项：key 是字母（单选/多选）或 T/F（判断），text 是选项文字 */
data class Option(
    val key: String,
    val text: String,
)

/**
 * 一道题：只保留刷题需要的字段，字段名与题库 JSON 一一对应。
 * answer 为空字符串时表示「无法判分」，界面必须按「本题暂无答案」处理。
 *
 * @param paper 题号所属的「套」编号（题库 JSON 里的 paper 字段，如 1/2）。
 *              v1.3 新增：题库换版本后题目 id 可能重新生成，进度迁移要靠
 *              (paper, type, no) 三元组对齐（见 ProgressMigrator），所以必须解析出来。
 *              默认 1：老题库没有这个字段时按第一套处理，不影响现有判分逻辑。
 */
data class Question(
    val id: String,
    val type: String,
    val no: Int,
    val stem: String,
    val image: String?,
    val options: List<Option>,
    val answer: String,
    val explain: String?,
    val paper: Int = 1,
) {
    /** 选项全部 key，用来把答案里的字母限制在真实选项内 */
    private val optionKeys: Set<String> get() = options.map { it.key }.toSet()

    /** 把答案字符串拆成选项 key 集合，例如 "AC" -> {A, C}，判断题 "T" -> {T} */
    val correctKeys: Set<String>
        get() = answer.trim()
            .filter { it in 'A'..'Z' || it in 'a'..'z' }
            .map { it.uppercaseChar().toString() }
            .filter { it in optionKeys }
            .toSet()

    /** 有答案才允许判分；空答案、或答案字母不在选项里，都按无法判分处理 */
    val hasAnswer: Boolean get() = correctKeys.isNotEmpty()

    /** 多选题要等用户点「确认」才提交，单选与判断题点一下就提交 */
    val isMulti: Boolean get() = type == "multi"

    /** 题型的中文名，显示在答题页顶部 */
    val typeName: String
        get() = when (type) {
            "single" -> "单选题"
            "judge" -> "判断题"
            "multi" -> "多选题"
            else -> "题目"
        }

    /** 正确选项的中文展示：判断题给「对/错」，其余给字母（多选用顿号连接） */
    fun answerDisplay(): String {
        val correct = options.filter { it.key in correctKeys }
        if (correct.isEmpty()) return "暂无"
        return if (type == "judge") {
            correct.joinToString("、") { it.text }
        } else {
            correct.joinToString("、") { it.key }
        }
    }
}
