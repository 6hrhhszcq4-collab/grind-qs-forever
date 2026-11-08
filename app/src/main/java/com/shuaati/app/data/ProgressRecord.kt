// 数据层：单题进度的数据结构（从 ProgressStore.kt 拆出来的独立文件）
//
// 为什么单独放一个文件：本文件不 import 任何 android.* 或 org.json.*，
// 所以能在电脑上直接用 kotlinc 编译、跑断言测试。
// SmartPicker 的权重公式与 Fsrs 的遗忘曲线只依赖这一个数据结构，纯 Kotlin 即可验证。
package com.shuaati.app.data

/**
 * 单题进度：字段含义与任务书一致。
 *
 * @param seen 出现（作答）次数
 * @param correct 答对次数
 * @param wrong 答错次数
 * @param streak 连续答对次数（答错一次归零），错题本靠它决定是否毕业
 * @param last 最后一次作答日期，yyyy-MM-dd（只有日期，没有时分秒，遗忘曲线按自然日算）
 * @param recent 最近若干次作答的对错，1 = 对、0 = 错；最新的排在最后，最多留 [RECENT_MAX] 个。
 *               空列表表示「还没有判过对错」，不是「全错」。正确率 acc 由它现算，不单独存。
 * @param stability 记忆稳定度 S（单位「天」）。0 表示还没学过；只有作答后才会被写成 >= 0.01。
 *                  06B 新增，老数据迁移时按 Fsrs.migratedStability 初始化，绝不一律填 0。
 * @param difficulty 难度 D，范围 1~10，默认 5.0。06B 新增，只有答错时才上升。
 */
data class ProgressRecord(
    val seen: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val streak: Int = 0,
    val last: String = "",
    val recent: List<Int> = emptyList(),
    val stability: Double = 0.0,
    val difficulty: Double = Fsrs.DEFAULT_DIFFICULTY,
) {
    companion object {
        /**
         * recent 最多保留多少个。
         * 10 次是一个折中：太少会让正确率忽上忽下（刚答对一次就从 0 跳到 1），
         * 太多会让「最近真的在进步」反映得太慢。
         */
        const val RECENT_MAX = 10

        /** 把一次作答追加进 recent：新的往后加，超过 [RECENT_MAX] 丢弃最旧的 */
        fun appendRecent(recent: List<Int>, correct: Boolean): List<Int> {
            val added = recent + if (correct) 1 else 0
            return if (added.size > RECENT_MAX) added.takeLast(RECENT_MAX) else added
        }
    }
}
