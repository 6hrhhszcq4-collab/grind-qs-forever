// 数据层：智能刷的权重公式与抽题算法（纯函数）
//
// 本文件故意不 import android.*，也不读文件、不碰界面：只吃「题目 + 进度 + 今天 + 随机源」。
// 这样有三个好处：
//   1. 能在电脑上用 kotlinc 直接编译并跑断言（_tmp/FsrsTest.kt）；
//   2. 能用 Python 照抄同一套公式做 3000 次模拟（tools/sim_fsrs.py）互相对账；
//   3. 权重公式只有这一份实现，界面层不许再写一遍（任务书硬性要求 6）。
//
// 06B 起遗忘项从「线性加成」换成 FSRS 的「记忆可提取度 R」：
//   R 越低（快忘了）→ 权重越高；R 越高（记得牢）→ 权重越低。
package com.shuaati.app.data

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 一道题在某一刻的三个数。
 * 单独做成数据类是为了让日志、报告、测试都能引用同一份口径——
 * 想核对「这道题权重为什么是 1.3」时不用在三个地方各算一遍。
 */
data class SmartScore(
    /** FSRS 的「今天还能记住的概率」R：0 = 完全忘了/没学过，1 = 今天刚答对 */
    val r: Double,
    /** 出现率：seen / max(1, avgSeen)，上限 1.0 */
    val occ: Double,
    /** 最终权重：不小于 0.05 */
    val weight: Double,
)

/**
 * 智能刷的抽题器。
 *
 * 公式（任务书 06B 第 10 条，改公式只改这里）：
 *   occ = seen / max(1, avgSeen)，上限 1.0（avgSeen = 全部题 seen 之和 / 题目总数）
 *   R   = Fsrs.retrievability(record, today)
 *         （seen == 0 或 S <= 0 → 0；t == 0 → 1；否则 (1 + (19/81)·t/S)^(-0.5)）
 *   w   = (1 − R) × 1.6 + (1 − occ) × 0.6，最小取 0.05
 *
 * 为什么用 (1 − R)：R 高表示记得牢，应该少出现；R 低表示快忘了，应该多出现。
 * 写成 R 就会把「熟题反而不出现」变成「生题反而不出现」，是最容易犯的错。
 */
object SmartPicker {

    /** 一组多少题 */
    const val GROUP_SIZE = 20

    /** 权重下限：防止 w 为 0 让加权随机死循环，也防止「全对且刚刷过」的题被永久排除 */
    const val MIN_WEIGHT = 0.05

    /** 遗忘项的系数（R 越低权重越高） */
    const val W_RETRIEVAL = 1.6

    /** 出现率项的系数 */
    const val W_OCC = 0.6

    /**
     * 理论最大权重：R 最小 0、occ 最小 0 → 1.6 + 0.6 = 2.2。
     * 写在这里是为了报告和测试能直接引用，不用手抄数字。
     */
    const val MAX_WEIGHT = W_RETRIEVAL + W_OCC

    /** 平均出现次数：全部题 seen 之和 / 题目总数；空题库返回 0 */
    fun averageSeen(records: List<ProgressRecord>): Double =
        if (records.isEmpty()) 0.0 else records.sumOf { it.seen }.toDouble() / records.size

    /**
     * 出现率：seen / max(1, avgSeen)，上限 1.0。
     * 分母取 max(1, avgSeen)：全班平均还不到 1 次时，不让「刷过 1 次」的题被放大成高出现率；
     * 上限 1.0：刷得特别多的题不会让 (1 − occ) 变成负数把权重拉低到 0 以下。
     */
    fun occurrence(seen: Int, avgSeen: Double): Double {
        val base = max(1.0, avgSeen)
        return min(1.0, seen / base)
    }

    /**
     * 一道题的完整权重。
     * w = (1 − R) × 1.6 + (1 − occ) × 0.6，最后把下限抬到 [MIN_WEIGHT]。
     * 下限只抬不降：w 永远 > 0，加权随机不会死循环，熟题也不会被永久排除（只是出现得少）。
     */
    fun score(record: ProgressRecord, avgSeen: Double, today: LocalDate): SmartScore {
        val occ = occurrence(record.seen, avgSeen)
        val r = Fsrs.retrievability(record, today)
        val raw = (1.0 - r) * W_RETRIEVAL + (1.0 - occ) * W_OCC
        return SmartScore(r = r, occ = occ, weight = max(MIN_WEIGHT, raw))
    }

    /** 只要权重数字的场合用这个（等价于 score(...).weight） */
    fun weight(record: ProgressRecord, avgSeen: Double, today: LocalDate): Double =
        score(record, avgSeen, today).weight

    /**
     * 抽一组题。
     *
     * 规则（任务书第 10 条，新题优先分支不许动）：
     *   第一步：所有 seen == 0 的题（从没刷过）优先，直到凑够 count 题或新题抽完；
     *   第二步：新题不够时，剩下的从其余题里「按权重加权随机」抽，不是排序取前 N；
     *   第三步：一组之内不重复。
     *
     * @param questions 整个题库（300 题）
     * @param progressOf 取某题进度（App 里传 store::recordOf），抽题时只读不写
     * @param count 一组题量，默认 [GROUP_SIZE] 即 20
     * @param today 今天（只用来算 R；测试可以传固定日期）
     * @param random 随机源（测试可传固定种子，复现同一组结果）
     */
    fun pick(
        questions: List<Question>,
        progressOf: (String) -> ProgressRecord,
        count: Int = GROUP_SIZE,
        today: LocalDate = LocalDate.now(),
        random: Random = Random.Default,
    ): List<Question> {
        if (questions.isEmpty() || count <= 0) return emptyList()
        val target = min(count, questions.size)

        // 先给每道题拍一张进度快照：抽题过程中不再回查，保证「新题优先」和「加权随机」
        // 两步看到的是同一份数据（万一 store 在抽题途中有变化也不会前后矛盾）
        val snapshot = questions.associate { it.id to progressOf(it.id) }

        val picked = ArrayList<Question>(target)

        // 第一步：从没刷过的题优先。新题之间没有「谁更该先抽」的差别，
        // 所以打乱后取——这也保证「同一个老进度连续抽两次」不会总是同样的 20 道新题。
        val fresh = questions.filter { snapshot.getValue(it.id).seen == 0 }.shuffled(random)
        if (fresh.size >= target) {
            picked.addAll(fresh.take(target))
            // 组内顺序也打乱：否则「权重最高的题」每次都排第一个，同一道题在连续 20 组里位置不变
            return picked.shuffled(random)
        }
        picked.addAll(fresh)

        // 第二步：新题不够，剩下的从已刷过的题里按权重加权随机
        val rest = questions.filter { snapshot.getValue(it.id).seen > 0 }.toMutableList()
        // avgSeen 用「全部题」算（包含新题），与公式定义一致；抽的过程中不重算
        val avgSeen = averageSeen(questions.map { snapshot.getValue(it.id) })
        var need = target - picked.size
        while (need > 0 && rest.isNotEmpty()) {
            picked.add(rest.removeAt(pickWeightedIndex(rest, snapshot, avgSeen, today, random)))
            need--
        }
        // 同上：组内顺序随机，让同一道题在连续几组里出现在不同位置
        return picked.shuffled(random)
    }

    /**
     * 按权重随机取一个下标。
     *
     * 做法：把候选题的权重首尾相接排成一条长度 total 的线段，
     * 取一个 [0, total) 的均匀随机点，落在谁的区间里就选谁。
     * 这样权重大的题区间长、更容易被点中，但权重小的题也始终有机会。
     *
     * 坚决不写成「按权重排序取前 N」：那样每次抽到的都是同样那几道，
     * 而且组内位置固定，一眼就能看出没在随机。
     */
    private fun pickWeightedIndex(
        candidates: List<Question>,
        snapshot: Map<String, ProgressRecord>,
        avgSeen: Double,
        today: LocalDate,
        random: Random,
    ): Int {
        val weights = DoubleArray(candidates.size)
        var total = 0.0
        for (i in candidates.indices) {
            // 每道题权重都 >= MIN_WEIGHT，所以 total 一定 > 0，
            // random.nextDouble() * total 永远能落在某个区间里，不会出现死循环
            val w = weight(snapshot.getValue(candidates[i].id), avgSeen, today)
            weights[i] = w
            total += w
        }
        var point = random.nextDouble() * total
        for (i in weights.indices) {
            point -= weights[i]
            if (point < 0.0) return i
        }
        // 浮点累加的极小误差兜底：理论上走不到这里，但兜底保证一定返回一个合法下标
        return weights.size - 1
    }
}
