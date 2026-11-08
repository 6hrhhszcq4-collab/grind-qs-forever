// 数据层：判分的唯一实现。统一刷、题型专练、错题本、模拟考试都走这里，
// 不允许在别的文件里再写一遍「集合相等就算对」之类的规则，避免两套玩法判分慢慢跑偏。
package com.shuaati.app.data

/** 一道题的判分结果。NO_ANSWER 表示题库还没给这道题答案：既不判对，也不算错。 */
enum class GradeResult {
    /** 答对 */
    CORRECT,

    /** 答错：有答案，但选的选项和正确答案不一致；未作答也走这里（未作答算错） */
    WRONG,

    /** 本题没有答案，不参与判分、不计入总分 */
    NO_ANSWER,
}

/**
 * 判分规则（全工程唯一入口）：
 * - 有答案的题：选中集合与正确答案集合**完全相等**才算对；
 *   多选少选（{A,B} 对 {A,B,C}）、多选（{A,B,C,D} 对 {A,B,C}）都不相等，因此都是 0 分，不给部分分。
 * - 没有答案的题（answer 为空，或答案字母不在选项里）：返回 NO_ANSWER，由调用方剔除。
 */
object Grading {

    /** 判一道题。selected 是用户选的选项 key 集合，未作答传空集。 */
    fun grade(question: Question, selected: Set<String>): GradeResult {
        if (!question.hasAnswer) return GradeResult.NO_ANSWER
        return if (selected == question.correctKeys) GradeResult.CORRECT else GradeResult.WRONG
    }

    /** 只要「对不对」的场合用这个；无答案题返回 false，调用方应先看 grade() */
    fun isCorrect(question: Question, selected: Set<String>): Boolean =
        grade(question, selected) == GradeResult.CORRECT

    /** 本题满分：有答案 1 分，无答案 0 分（无答案题从总分里剔除） */
    fun fullScore(question: Question): Int = if (question.hasAnswer) 1 else 0

    /**
     * 及格线：满分的 60%，向上取整。
     * 满分 100 时正好 60 分，与任务书一致；题库缺答案导致满分不足 100 时，
     * 及格线按同比例缩小，不会出现「满分 78 却要求 60 分及格」这种不可能达成的情况。
     */
    fun passLine(fullScore: Int): Int = (fullScore * 6 + 9) / 10

    /** 正确答案的中文展示（给结果页用）：判断题显示「对/错」，单选多选显示字母 */
    fun answerText(question: Question): String = question.answerDisplay()

    /** 用户答案的中文展示：未作答显示「未作答」，判断题显示「对/错」，其余显示字母 */
    fun selectedText(question: Question, selected: Set<String>): String {
        if (selected.isEmpty()) return "未作答"
        if (question.type == "judge") {
            return question.options
                .filter { it.key in selected }
                .joinToString("、") { it.text }
        }
        return selected.sorted().joinToString("、")
    }
}
