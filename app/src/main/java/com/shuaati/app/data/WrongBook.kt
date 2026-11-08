// 数据层：错题本的唯一判定规则
// 错题完全由 progress.json 里的 wrong / streak 推导出来，不另外保存「错题列表」文件，
// 所以永远不会出现「两份数据对不上」的情况。
package com.shuaati.app.data

/**
 * 错题判定与筛选。
 * 这里全是纯函数：只依赖传进来的数字与列表，自己不读文件、不碰 Android，
 * 以后换界面、换存储方式都能直接复用。
 */
object WrongBook {

    /** 连续答对达到这个次数，本题就从错题本毕业（下一次进错题本列表不再出现） */
    const val GRADUATE_STREAK = 3

    /**
     * 一道题算不算错题：错过（wrong > 0）并且还没连对到 3 次。
     * 这是全工程唯一的判定入口；界面上的数量、列表、刷题顺序都走这里，保证三者永远一致。
     */
    fun isWrong(wrong: Int, streak: Int): Boolean = wrong > 0 && streak < GRADUATE_STREAK

    /** 同上，参数换成一条进度记录，调用处不用拆字段 */
    fun isWrong(record: ProgressRecord): Boolean = isWrong(record.wrong, record.streak)

    /**
     * 从题库里筛出错题，保持题库原来的顺序（题号从小到大），错题本列表就按这个顺序显示。
     * progressOf 由调用方提供（App 里传 ProgressStore::recordOf），这样本函数不依赖 Android。
     */
    fun filter(questions: List<Question>, progressOf: (String) -> ProgressRecord): List<Question> =
        questions.filter { isWrong(progressOf(it.id)) }

    /**
     * 错题数量。
     * 故意用 filter(...).size 而不是另写一遍计数逻辑，保证「数量 N」和「列表条数」必然相等。
     */
    fun count(questions: List<Question>, progressOf: (String) -> ProgressRecord): Int =
        filter(questions, progressOf).size

    /** 离毕业还差几次连对；不在错题本里时返回 0（界面可用来提示「再对 2 次移出」） */
    fun remainingStreak(record: ProgressRecord): Int =
        if (isWrong(record)) GRADUATE_STREAK - record.streak else 0
}
