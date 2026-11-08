// 界面层（纯计算）：首页统计面板的数字。
//
// 这些数字**全部**从现有 progress.json 现算，不新增任何存储、不写死任何示例数字：
//   本轮进度 = _round 已出题数 / 题库总数
//   累计做题 = 所有题 seen 之和
//   正确率   = 所有题 correct 之和 ÷ 所有题 seen 之和（没有作答时给 null，界面显示「—」）
//   错题数   = WrongBook.isWrong(record) 为真的题数（复用错题本同一个判定，保证「N」处处一致）
//   已掌握   = wrong > 0 且 streak >= 3 的题数
//   分题型   = 该类里 seen > 0 的题数 / 该类总题数
//   连续天数 = 所有题的 last 取不重复日期，从今天往回数连续有几天
package com.shuaati.app.ui

import com.shuaati.app.data.ProgressStore
import com.shuaati.app.data.Question
import com.shuaati.app.data.RoundKeys
import com.shuaati.app.data.WrongBook
import java.time.LocalDate

/** 一个题型的进度：做过几题 / 该类总题数 */
data class TypeProgress(
    val typeName: String,
    val done: Int,
    val total: Int,
)

/** 首页统计面板的全部数字 */
data class HomeStats(
    /** 题库总数 */
    val totalQuestions: Int,
    /** 本轮（统一刷的 _round）已出题数 */
    val roundCount: Int,
    /** 所有题 seen 之和 */
    val totalSeen: Int,
    /** 所有题 correct 之和 */
    val totalCorrect: Int,
    /** 正确率百分比 0~100；没有任何作答时为 null（界面显示「—」） */
    val accuracyPercent: Int?,
    /** 错题数（wrong > 0 且 streak < 3） */
    val wrongCount: Int,
    /** 已掌握（wrong > 0 且 streak >= 3） */
    val masteredCount: Int,
    /** 单选题进度 */
    val single: TypeProgress,
    /** 判断题进度 */
    val judge: TypeProgress,
    /** 多选题进度 */
    val multi: TypeProgress,
    /** 连续刷题天数（今天没刷就是 0） */
    val streakDays: Int,
)

/**
 * 从题库 + 进度算出首页要显示的全部数字。
 * 纯函数：同样的输入永远得到同样的输出，不读文件、不写文件，方便对账。
 *
 * @param today 今天（默认取系统日期；测试可以传固定日期）
 */
fun computeHomeStats(
    questions: List<Question>,
    store: ProgressStore,
    today: LocalDate = LocalDate.now(),
): HomeStats {
    val records = questions.map { store.recordOf(it.id) }
    val totalSeen = records.sumOf { it.seen }
    val totalCorrect = records.sumOf { it.correct }

    // 正确率：分母为 0 时给 null，界面显示「—」，不要显示 0%（那会误导成「全错」）
    val accuracy = if (totalSeen > 0) {
        Math.round(totalCorrect * 100.0 / totalSeen).toInt()
    } else {
        null
    }

    // 错题与已掌握都直接读 wrong / streak，判定复用一个函数，保证与错题本列表同源
    var wrong = 0
    var mastered = 0
    records.forEach { r ->
        if (WrongBook.isWrong(r)) wrong++
        if (r.wrong > 0 && r.streak >= WrongBook.GRADUATE_STREAK) mastered++
    }

    return HomeStats(
        totalQuestions = questions.size,
        roundCount = store.roundCount(RoundKeys.UNIFIED),
        totalSeen = totalSeen,
        totalCorrect = totalCorrect,
        accuracyPercent = accuracy,
        wrongCount = wrong,
        masteredCount = mastered,
        single = typeProgress(questions, "single", "单选题", store),
        judge = typeProgress(questions, "judge", "判断题", store),
        multi = typeProgress(questions, "multi", "多选题", store),
        streakDays = streakDays(questions, store, today),
    )
}

/** 单个题型的「做过几题 / 总数」 */
private fun typeProgress(
    questions: List<Question>,
    type: String,
    typeName: String,
    store: ProgressStore,
): TypeProgress {
    val all = questions.filter { it.type == type }
    val done = all.count { store.recordOf(it.id).seen > 0 }
    return TypeProgress(typeName = typeName, done = done, total = all.size)
}

/**
 * 连续刷题天数。
 *
 * 做法：把每道题的 last（yyyy-MM-dd）收集成日期集合，从今天开始一天一天往回看，
 * 哪天没有作答记录就停。所以「今天做过至少一题」今天就算 1 天，昨天也做过就是 2 天。
 * 今天一题没做 → 0 天（「连续」要求今天在内，这里不做「昨天也算」的讨巧）。
 */
private fun streakDays(questions: List<Question>, store: ProgressStore, today: LocalDate): Int {
    val dates = HashSet<LocalDate>()
    questions.forEach { q ->
        val last = store.recordOf(q.id).last
        if (last.isNotBlank()) {
            // 手改坏的日期直接跳过，不让一条脏数据把首页搞崩
            runCatching { LocalDate.parse(last.trim()) }.getOrNull()?.let { dates.add(it) }
        }
    }
    var days = 0
    var cursor = today
    while (dates.contains(cursor)) {
        days++
        cursor = cursor.minusDays(1)
    }
    return days
}
