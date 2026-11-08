// 通用组件：页面顶栏（返回 + 标题 + 右侧动作）
//
// 六个页面原来各写一套 Row + TextButton，间距和字号都不一样；
// 统一到这里之后，顶栏高度、返回文案、标题字号全工程一致。
package com.shuaati.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 页面顶栏。
 *
 * @param title 标题
 * @param onBack 返回回调；传 null 表示这个页面从底部导航进入，没有返回按钮
 * @param backText 返回按钮文字，默认「返回」
 * @param action 右侧动作区（例如「全部开始」「交卷」）
 * @param divider 是否在底部画一条分割线
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backText: String = "返回",
    divider: Boolean = true,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = appColors()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                AppButton(
                    text = backText,
                    onClick = onBack,
                    style = AppButtonStyle.TEXT,
                    fillWidth = false,
                )
            } else {
                Spacer(Modifier.width(Spacing.lg))
            }
            Text(
                text = title,
                modifier = Modifier.padding(start = Spacing.xs),
                style = AppText.stemStrong,
                color = c.textPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (action != null) {
                action()
                Spacer(Modifier.width(Spacing.xs))
            }
        }
        if (divider) {
            Spacer(Modifier.height(Spacing.xs))
            HorizontalDivider(color = c.border, thickness = ComponentSize.stroke)
        }
    }
}
