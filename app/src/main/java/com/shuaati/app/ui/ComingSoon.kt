// 界面层：还没做的功能统一弹这一句，避免每个页面各写一套 Toast
package com.shuaati.app.ui

import android.content.Context
import android.widget.Toast

/**
 * 弹一条「即将上线」提示。
 * 本任务（06A）里「导入新试卷」「切换试卷」都只放按钮，点了就弹这句，真正功能在 06B。
 */
fun showComingSoon(context: Context) {
    Toast.makeText(context, "即将上线", Toast.LENGTH_SHORT).show()
}
