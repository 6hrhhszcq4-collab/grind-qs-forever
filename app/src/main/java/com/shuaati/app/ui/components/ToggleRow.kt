// 通用组件：一行开关（左侧标题，右侧 Switch）
//
// 07 从设置页的私有 SwitchRow 提出来，供「设置 → 首页显示」和「首次引导第 6 屏」共用，
// 保证两处开关的颜色、字号、间距、点击区域完全一致（改一处两处同时生效）。
// 整行可点；Switch 自己不再接收点击，避免一次点击被处理两次。
package com.shuaati.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 一行开关。
 *
 * @param title 开关名称（16sp 正文色）
 * @param checked 当前是否打开
 * @param onCheckedChange 点击整行时回调，参数是「点完之后应该是的状态」
 * @param hint 可选的一行小字说明，显示在标题下面（12sp 次要色）；不传就不占位
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val c = appColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AppText.body,
                color = c.textPrimary,
            )
            if (hint != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = hint,
                    style = AppText.caption,
                    color = c.textSecondary,
                )
            }
        }
        Switch(
            checked = checked,
            // 整行的 clickable 已经处理点击，这里传 null 避免点两次
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.primary,
                checkedBorderColor = c.primary,
                uncheckedThumbColor = c.textSecondary,
                uncheckedTrackColor = c.fill,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}
