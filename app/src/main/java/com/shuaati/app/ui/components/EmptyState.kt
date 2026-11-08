// 通用组件：空状态（居中文案 + 一个按钮）
//
// 错题本为空、题库为空这类场景统一用它，不要每个页面各自摆一个 Column。
package com.shuaati.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 空状态。
 *
 * @param text 主文案（例如「还没有错题，去刷几道题吧」）
 * @param hint 可选补充说明，12sp 次要色
 * @param actionText 按钮文字；不传就不显示按钮
 * @param onAction 按钮点击
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = appColors()
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = AppText.body,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = hint,
                style = AppText.caption,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Spacing.xl))
            AppButton(text = actionText, onClick = onAction, fillWidth = false)
        }
    }
}
