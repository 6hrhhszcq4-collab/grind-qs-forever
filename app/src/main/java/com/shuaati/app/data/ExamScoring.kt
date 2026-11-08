// 数据层：模拟考试的算分。
// 这里全是纯函数：只依赖题目和作答，不读文件、不碰界面、不依赖 Android，
// 所以可以在电脑上直接跑同一份代码验证规则（本题库当前没有空答案题，
// 空答案剔除这条规则就是靠这份纯函数在 JVM 上验证的，详见 docs/验收报告/05-报告.md）。
package com.shuaati.app.data

/** 结果页的一行：卷内第几题、题目、用户选了什么、判分结果 */
data class ExamItem(
    val order: Int,
    val question: Question,
    val selected: Set<String>,
    val grade: GradeResult,
) {
    /** 这道题有没有作答（选过任何选项） */
    val answered: Boolean get() = selected.isNotEmpty()
}

/**
 * 交卷后的算分结果。只放在内存里，不落盘、不留历史成绩。
 * 统计口径：对 + 错（答了但错）+ 未答（有答案但空白）+ 无答案（不计分）= 试卷总题数。
 */
data class ExamResult(
    val items: List<ExamItem>,
    val score: Int,
    val fullScore: Int,
    val passLine: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val unansweredCount: Int,
    val noAnswerCount: Int,
    val autoSubmitted: Boolean,
) {
    val passed: Boolean get() = score >= passLine
}

object ExamScoring {

    /**
     * 按试卷顺序算分。
     * @param questions 试卷题目（顺序即卷内题号）
     * @param answers 题目 id → 选中的选项集合；没作答的题不在 Map 里
     * @param autoSubmitted 是否是倒计时归零自动交的卷（结果页要显示提示）
     */
    fun score(
        questions: List<Question>,
        answers: Map<String, Set<String>>,
        autoSubmitted: Boolean = false,
    ): ExamResult {
        val items = questions.mapIndexed { index, question ->
            val selected = answers[question.id].orEmpty()
            ExamItem(
                order = index + 1,
                question = question,
                selected = selected,
                // 判分规则统一在 Grading 里：有答案且集合完全相等才得分，无答案返回 NO_ANSWER
                grade = Grading.grade(question, selected),
            )
        }

        val fullScore = items.sumOf { Grading.fullScore(it.question) }
        val correct = items.count { it.grade == GradeResult.CORRECT }
        // 未作答算错（0 分），但统计上单独算一类，方便考生知道是「不会」还是「没来得及」
        val wrong = items.count { it.grade == GradeResult.WRONG && it.answered }
        val unanswered = items.count { it.grade == GradeResult.WRONG && !it.answered }
        val noAnswer = items.count { it.grade == GradeResult.NO_ANSWER }

        return ExamResult(
            items = items,
            score = correct,
            fullScore = fullScore,
            passLine = Grading.passLine(fullScore),
            correctCount = correct,
            wrongCount = wrong,
            unansweredCount = unanswered,
            noAnswerCount = noAnswer,
            autoSubmitted = autoSubmitted,
        )
    }
}
