// 通用组件：小标签（11 新增）。
//
// 用途只有两个：试卷列表里标「本地」/「源名」，以及对话框里的小提示。
// 颜色、字号、间距、圆角全部来自设计令牌，不引入新的视觉：
//   - PRIMARY：浅主色底 + 主色字（云端试卷、源名）
//   - WARNING：浅警告底 + 警告字（本地试卷、未上传）
//   - NEUTRAL：中性填充底 + 次要字
package com.shuaati.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/** 标签配色 */
enum class TagTone {
    /** 浅主色底（云端 / 源名） */
    PRIMARY,

    /** 浅警告底（本地） */
    WARNING,

    /** 中性填充（普通提示） */
    NEUTRAL,
}

/**
 * 小标签。
 * @param text 标签文字（保持很短：2~8 个字，或一个源名）
 * @param tone 配色
 */
@Composable
fun Tag(
    text: String,
    tone: TagTone = TagTone.PRIMARY,
    modifier: Modifier = Modifier,
) {
    val c = appColors()
    val background = when (tone) {
        TagTone.PRIMARY -> c.primarySoft
        TagTone.WARNING -> c.warningSoft
        TagTone.NEUTRAL -> c.fill
    }
    val foreground = when (tone) {
        TagTone.PRIMARY -> c.primary
        TagTone.WARNING -> c.warning
        TagTone.NEUTRAL -> c.textSecondary
    }
    Surface(
        modifier = modifier,
        shape = Radius.labelShape,
        color = background,
        contentColor = foreground,
    ) {
        Text(
            text = text,
            style = AppText.caption,
            color = foreground,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}
