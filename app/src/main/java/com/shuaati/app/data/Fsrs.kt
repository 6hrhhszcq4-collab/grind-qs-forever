// 数据层：FSRS-4.5 遗忘曲线的纯函数实现（06B 新增）。
//
// 本文件故意不 import 任何 android.* 或 org.json.*，所以能在电脑上用 kotlinc 直接编译、跑断言
// （_tmp/FsrsTest.kt），也能和 tools/sim_fsrs.py 里的 Python 版公式逐项对账。
//
// 只有两个评分档：答对 = Good（grade 3），答错 = Again（grade 1），与 App 的判分结果一一对应。
//
// 公式与参数全部来自任务 06B 第 10 条，改公式只改这一个文件：
//   R(t) = (1 + (19/81) * t / S) ^ (-0.5)      t = 距上次作答的自然日天数
//   首次学习（seen == 0）：答对 S = W[2]、D = W[4]；答错 S = W[0]、D = W[5]
//   之后：按 FSRS-4.5 的 Good / Again 两条公式更新 S，D 只在答错时上升
//
// 数值安全（硬性要求 6A）：
//   1. S 先夹到 [0.01, 36500]，再算 S^(-W[9])；顺序反了 S 极小时会算出 Infinity；
//   2. D 夹到 [1, 10]，并且 D 只参与正数幂（D^(-W[12])），不会出现 NaN；
//   3. 所有结果最后再夹一次，调用方拿到的永远是可写进 JSON 的有限数。
package com.shuaati.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.pow

/**
 * 一道题的 FSRS 参数与计算。
 *
 * @see retrievability 「今天还能记住的概率」R
 * @see next 答完一题后的新 S / D
 */
object Fsrs {

    /** 记忆稳定度下限（天）。比这更小的值一律抬到 0.01，保证 S^(-W[9]) 不会溢出 */
    const val MIN_STABILITY = 0.01

    /** 记忆稳定度上限（约 100 年，天）。防止长期连对把 S 撑到 Double 上限 */
    const val MAX_STABILITY = 36500.0

    /** 难度下限 */
    const val MIN_DIFFICULTY = 1.0

    /** 难度上限 */
    const val MAX_DIFFICULTY = 10.0

    /** 难度的默认值：没学过、或者老数据迁移时统一给 5.0 */
    const val DEFAULT_DIFFICULTY = 5.0

    /** FSRS-4.5 的 17 个默认权重，顺序不许动 */
    val W = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.0310,
        1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.5870, 0.2272, 2.8755,
    )

    /**
     * 夹取记忆稳定度。
     * NaN 按「还没学过」处理（0.01 是下限，不是 0——S 为 0 时 R 公式会除零）。
     */
    fun clampStability(raw: Double): Double =
        if (raw.isNaN()) MIN_STABILITY else raw.coerceIn(MIN_STABILITY, MAX_STABILITY)

    /** 夹取难度；NaN 回落到默认 5.0 */
    fun clampDifficulty(raw: Double): Double =
        if (raw.isNaN()) DEFAULT_DIFFICULTY else raw.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    /**
     * 距上次作答的自然日天数。
     * - last 为空或解析不了（老文件、手工改坏）：返回 0，当作「今天刚答过」；
     *   这样老题不会被误判成「很久没复习」而顶到最高权重。
     * - last 在未来（手机时间被调回去过）：也返回 0，不出现负数天数。
     */
    fun daysSince(last: String, today: LocalDate): Long {
        if (last.isBlank()) return 0L
        val date = runCatching { LocalDate.parse(last.trim()) }.getOrNull() ?: return 0L
        return ChronoUnit.DAYS.between(date, today).coerceAtLeast(0L)
    }

    /**
     * 「今天还能记住的概率」R。
     *
     * @param stability 记忆稳定度 S（天）；<= 0 或 NaN 表示还没学过，直接返回 0
     * @param days 距上次作答的天数；<= 0（今天刚答过）返回 1
     */
    fun retrievability(stability: Double, days: Long): Double {
        if (stability.isNaN() || stability <= 0.0) return 0.0
        if (days <= 0L) return 1.0
        // 先夹 S 再参与除法：S 极小时 (1 + 大数) 仍然有限，^-0.5 只会变小不会溢出
        val s = clampStability(stability)
        val ratio = (19.0 / 81.0) * days.toDouble() / s
        return (1.0 + ratio).pow(-0.5)
    }

    /**
     * 从一条进度记录算 R。
     * seen == 0（从没学过）或 S <= 0 → 0，这样新题在加权分支里的权重天然最高。
     */
    fun retrievability(record: ProgressRecord, today: LocalDate): Double {
        if (record.seen <= 0 || record.stability <= 0.0) return 0.0
        return retrievability(record.stability, daysSince(record.last, today))
    }

    /** 首次答对的 S：W[2] ≈ 3.71 天 */
    fun initialStability(correct: Boolean): Double =
        clampStability(if (correct) W[2] else W[0])

    /** 首次答对的 D：W[4] ≈ 5.16；答错的 D：W[5] ≈ 1.23 */
    fun initialDifficulty(correct: Boolean): Double =
        clampDifficulty(if (correct) W[4] else W[5])

    /**
     * 答完一题后新的 (S, D)。
     *
     * @param record 本次作答**之前**的进度记录（seen / S / D / last 都是旧值）
     * @param correct 这次答对了吗
     * @param today 今天（自然日）
     * @return Pair(新 S, 新 D)，两个值都已经夹好
     */
    fun next(record: ProgressRecord, correct: Boolean, today: LocalDate): Pair<Double, Double> {
        // 首次学习：还没有 S（seen == 0 或 S <= 0）
        if (record.seen <= 0 || record.stability <= 0.0) {
            return initialStability(correct) to initialDifficulty(correct)
        }

        // 关键顺序：**先夹 S**，再用它算 S^(-W[9])。反过来 S = 1e-300 时幂会算出 Infinity。
        val s = clampStability(record.stability)
        val d = clampDifficulty(record.difficulty)
        val t = daysSince(record.last, today)
        val r = retrievability(s, t)

        if (correct) {
            // Good：S 按「当前可提取度」增长，D 不变
            val nextS = s * (
                1.0 + exp(W[8]) * (11.0 - d) * s.pow(-W[9]) * (exp((1.0 - r) * W[10]) - 1.0)
                )
            return clampStability(nextS) to clampDifficulty(d)
        }

        // Again：S 掉到「重新学习」的水平，D 上升（grade = 1 → (3 − 1) × W[6]）
        val nextS = W[11] * d.pow(-W[12]) * ((s + 1.0).pow(W[13]) - 1.0) * exp((1.0 - r) * W[14])
        return clampStability(nextS) to clampDifficulty(d + W[6] * 2.0)
    }

    /**
     * 老数据迁移用的 S 初值。
     *
     * 规则（任务书第 10 条）：S = max(0.5, recent 里 1 的个数 × 2.0)，recent 为空时 0.5。
     * 绝不能一律给 0：S = 0 会让所有老题同时变成「从没学过」，智能刷就被废掉了。
     */
    fun migratedStability(recent: List<Int>): Double =
        maxOf(0.5, recent.count { it == 1 } * 2.0)
}
