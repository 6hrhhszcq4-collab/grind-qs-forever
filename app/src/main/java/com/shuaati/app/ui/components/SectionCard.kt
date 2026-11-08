// 通用组件：分组卡片
//
// 白底（浅色）/ 深灰（深色）容器，圆角 16dp、内边距 16dp，可选一行标题。
// 页面里凡是「一组内容」都套它，不要各自写 Surface + RoundedCornerShape(10.dp)。
package com.shuaati.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * 分组卡片。
 *
 * @param title 可选标题行（18sp 加粗）；不传就不占位
 * @param subtitle 可选副标题，显示在标题右下（12sp 次要色）
 * @param outlined 是否要一圈细边框（默认不要，深色下靠底色区分层次）
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    outlined: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = appColors()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Radius.cardShape,
        color = c.surface,
        border = if (outlined) BorderStroke(ComponentSize.stroke, c.border) else null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, style = AppText.stemStrong, color = c.textPrimary)
                    if (subtitle != null) {
                        Spacer(Modifier.weight(1f))
                        Text(text = subtitle, style = AppText.caption, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(Spacing.md))
            }
            content()
        }
    }
}
