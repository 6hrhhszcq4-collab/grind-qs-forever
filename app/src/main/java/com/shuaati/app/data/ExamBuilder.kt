// 数据层：模拟考试的组卷规则。只在这里定义「考多少题、怎么抽、怎么打乱」，
// 题目本身全部来自 assets/questions.json，代码里不写死任何一道题。
package com.shuaati.app.data

import kotlin.random.Random

/**
 * 一份试卷。
 * questions 就是考生看到的题目顺序（组卷时已经整体打乱过一次）。
 * 三个 count 是实际抽到的题量，题库不足时会小于目标值，开始页靠它显示真实分布。
 */
data class ExamPaper(
    val questions: List<Question>,
    val singleCount: Int,
    val judgeCount: Int,
    val multiCount: Int,
) {
    /** 本卷实际题数 */
    val total: Int get() = questions.size

    /** 题库是否够抽满 100 题（不够时开始页要提示「本题库不足 100 题，本次共 N 题」） */
    val isFull: Boolean get() = total == ExamBuilder.TOTAL

    /** 本卷满分：每道有答案的题 1 分，没答案的题从总分里剔除 */
    val fullScore: Int get() = questions.count { it.hasAnswer }

    /** 本卷及格线（满分的 60%） */
    val passLine: Int get() = Grading.passLine(fullScore)

    /** 题库不足时的提示文案；够 100 题返回 null，界面不显示 */
    fun shortageText(): String? =
        if (isFull) null else "本题库不足 ${ExamBuilder.TOTAL} 题，本次共 $total 题"
}

/**
 * 组卷器。
 * 规则（硬性要求）：单选 60 + 判断 20 + 多选 20 = 100 题；
 * 三类各自随机抽完后混在一起，**再整体打乱一次**，所以前 10 题不会全是单选。
 * 某一类题池不够时能抽多少抽多少，不用其他题型去凑，避免分布失真。
 */
object ExamBuilder {

    /** 三类题型的目标题量 */
    const val SINGLE_COUNT = 60
    const val JUDGE_COUNT = 20
    const val MULTI_COUNT = 20

    /** 满卷题量 */
    const val TOTAL = SINGLE_COUNT + JUDGE_COUNT + MULTI_COUNT

    /** 考试时长（分钟） */
    const val DURATION_MINUTES = 90

    /** 考试时长（秒）；倒计时用这个值算结束时间戳 */
    const val DURATION_SECONDS = DURATION_MINUTES * 60

    /**
     * 组一份卷子。
     * @param all 整个题库
     * @param random 随机源；测试时可以传固定种子复现同一份卷子
     */
    fun build(all: List<Question>, random: Random = Random.Default): ExamPaper {
        val single = pick(all, "single", SINGLE_COUNT, random)
        val judge = pick(all, "judge", JUDGE_COUNT, random)
        val multi = pick(all, "multi", MULTI_COUNT, random)

        // 关键步骤：三类混在一起后整体再打乱一次，保证试卷顺序是乱的
        val mixed = (single + judge + multi).shuffled(random)

        return ExamPaper(
            questions = mixed,
            singleCount = single.size,
            judgeCount = judge.size,
            multiCount = multi.size,
        )
    }

    /** 从某一题型里随机抽 count 道；按 id 去重，题池不够就有多少拿多少 */
    private fun pick(
        all: List<Question>,
        type: String,
        count: Int,
        random: Random,
    ): List<Question> =
        all.asSequence()
            .filter { it.type == type }
            .distinctBy { it.id }
            .toList()
            .shuffled(random)
            .take(count)
}
