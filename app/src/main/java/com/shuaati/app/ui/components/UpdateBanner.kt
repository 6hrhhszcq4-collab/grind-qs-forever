// 通用组件（v1.6）：顶部提示黄条。
//
// 用于「应用有新版本」这类需要用户注意、但不该打断操作的提示：
// 橙色底 + 细边框，贴在页面顶部，右侧「更新」和「关闭」两个动作。
// 样式沿用 v1.3 之前试卷更新提示条的那套视觉。
package com.shuaati.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 顶部提示黄条。
 *
 * @param title 主文案（如「发现新版本 1.6」）
 * @param subtitle 副文案（如「点击更新，下载完成后自动打开安装」）
 * @param actionText 右侧动作文字（默认「更新」）
 * @param onAction 点动作
 * @param onDismiss 点关闭
 */
@Composable
fun UpdateBanner(
    title: String,
    subtitle: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionText: String = "更新",
) {
    val c = appColors()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        shape = Radius.buttonShape,
        color = c.warningSoft,
        border = BorderStroke(ComponentSize.stroke, c.warning),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = AppText.smallStrong, color = c.warning)
                Spacer(Modifier.width(Spacing.xs))
                Text(text = subtitle, style = AppText.caption, color = c.textSecondary)
            }
            TextButton(onClick = onAction) {
                Text(text = actionText, style = AppText.smallStrong, color = c.warning)
            }
            TextButton(onClick = onDismiss) {
                Text(text = "✕", style = AppText.body, color = c.textSecondary)
            }
        }
    }
}
