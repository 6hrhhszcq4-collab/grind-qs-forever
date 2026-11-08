// 界面状态层：模拟考试的全部可变状态。
// 只管三件事：当前第几题、每题选了什么、还剩多少时间；交卷时算分并把作答写回 progress.json。
// 界面（ExamScreen）只读这里的状态，不自己算对错、不自己算分数。
package com.shuaati.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shuaati.app.data.ExamResult
import com.shuaati.app.data.ExamScoring
import com.shuaati.app.data.ExamStore
import com.shuaati.app.data.GradeResult
import com.shuaati.app.data.ProgressStore
import com.shuaati.app.data.Question
import com.shuaati.app.data.SavedExam

/**
 * 一场考试。
 * @param questions 试卷题目（顺序就是组卷时打乱后的顺序）
 * @param startAt 开考时间戳
 * @param endAt 结束时间戳：倒计时永远用 endAt − 当前时间算，切后台、锁屏都不会算错
 */
class ExamState(
    val questions: List<Question>,
    private val progress: ProgressStore,
    private val examStore: ExamStore,
    private val startAt: Long,
    private val endAt: Long,
    private val durationSec: Int,
    initialAnswers: Map<String, Set<String>> = emptyMap(),
    initialIndex: Int = 0,
) {
    /** 当前第几题（从 0 开始） */
    var index by mutableStateOf(initialIndex.coerceIn(0, (questions.size - 1).coerceAtLeast(0)))
        private set

    /** 每题选中的选项 key；未作答的题不在 Map 里 */
    var answers by mutableStateOf(initialAnswers)
        private set

    /** 最近一次「看表」的时间，每秒刷新一次，只为让倒计时数字走下去 */
    var nowMillis by mutableStateOf(System.currentTimeMillis())
        private set

    /** 交卷后的结果；不为空就表示已经考完，界面切到结果页 */
    var result by mutableStateOf<ExamResult?>(null)
        private set

    init {
        // 一开考就落盘一次：避免「刚点开始就退出」时白丢一张卷子
        if (questions.isNotEmpty()) persist()
    }

    /** 试卷总题数 */
    val total: Int get() = questions.size

    /** 当前题；题池为空时为 null */
    val current: Question? get() = questions.getOrNull(index)

    /** 是不是第一题 / 最后一题（底部按钮据此置灰） */
    val isFirst: Boolean get() = index <= 0
    val isLast: Boolean get() = index >= total - 1

    /** 剩余秒数：用「结束时间戳 − 当前时间」算，不是每秒减 1 累加出来的 */
    val remainingSec: Int
        get() = (((endAt - nowMillis).coerceAtLeast(0L)) / 1000L).toInt()

    /** 剩 5 分钟及以内，倒计时文字变红 */
    val isTimeWarning: Boolean get() = remainingSec <= WARNING_SECONDS

    /** 已作答的题数 */
    val answeredCount: Int get() = questions.count { answers[it.id].orEmpty().isNotEmpty() }

    /** 还没作答的题数（交卷确认框里的 X） */
    val unansweredCount: Int get() = total - answeredCount

    /** 某道题是否已作答，答题卡格子用 */
    fun isAnswered(position: Int): Boolean =
        answers[questions.getOrNull(position)?.id].orEmpty().isNotEmpty()

    /**
     * 点一个选项。
     * 单选/判断：直接替换成这一项（点另一个就改答案，不判对错）；
     * 多选：点一下选中，再点一下取消；两种都是立刻写盘。
     */
    fun select(key: String) {
        val question = current ?: return
        if (result != null) return
        val old = answers[question.id].orEmpty()
        val next = if (question.isMulti) {
            if (key in old) old - key else old + key
        } else {
            setOf(key)
        }
        answers = if (next.isEmpty()) answers - question.id else answers + (question.id to next)
        persist()
    }

    /** 上一题 */
    fun prev() {
        if (index > 0) {
            index--
            persist()
        }
    }

    /** 下一题 */
    fun next() {
        if (index < total - 1) {
            index++
            persist()
        }
    }

    /** 答题卡点格子：直接跳到第 position 题（从 0 开始） */
    fun jumpTo(position: Int) {
        if (total == 0) return
        index = position.coerceIn(0, total - 1)
        persist()
    }

    /**
     * 每秒调一次：刷新「现在」，并在倒计时归零时自动交卷。
     * 因为用的是时间戳相减，所以 App 在后台待了多久，回来时间都是对的。
     */
    fun tick() {
        nowMillis = System.currentTimeMillis()
        if (result == null && nowMillis >= endAt) finish(auto = true)
    }

    /**
     * 交卷算分，并把每道题的作答写回 progress.json。
     * @param auto true 表示倒计时归零自动交卷（结果页会多一行提示）
     */
    fun finish(auto: Boolean = false): ExamResult {
        result?.let { return it }

        // 算分交给 data/ExamScoring.kt 的纯函数，界面状态这里只负责写回进度
        val finished = ExamScoring.score(questions, answers, auto)

        // 写回 progress.json：答对 correct+1 且 streak+1，答错 wrong+1 且 streak 清零，
        // 未作答算错（和统一刷一致）；无答案题只记「出现过」，既不判对也不算错。
        finished.items.forEach { item ->
            when {
                item.grade == GradeResult.NO_ANSWER -> if (item.answered) {
                    progress.markSeen(item.question.id)
                }
                item.grade == GradeResult.CORRECT -> progress.markAnswered(item.question.id, true)
                else -> progress.markAnswered(item.question.id, false)
            }
        }

        // 交完卷就删掉断点文件，下次进 App 不再弹「继续上次考试」
        examStore.clear()
        result = finished
        return finished
    }

    /** 放弃考试：只删 exam.json，不写 progress */
    fun abandon() {
        examStore.clear()
    }

    /** 把当前进度写进 exam.json（每次点选、翻页、跳题都会调用） */
    private fun persist() {
        if (questions.isEmpty()) return
        examStore.save(
            SavedExam(
                ids = questions.map { it.id },
                answers = answers.filterValues { it.isNotEmpty() },
                index = index,
                startAt = startAt,
                endAt = endAt,
                durationSec = durationSec,
                savedRemainingSec = remainingSec,
            )
        )
    }

    companion object {
        /** 剩余多少秒开始变红：5 分钟 */
        const val WARNING_SECONDS = 5 * 60
    }
}
