// 通用组件：单选行（左侧文字 + 右侧圆点，整行可点）
//
// 原来只在设置页「启动页」里用（私有实现），07A 之后引导页第 6 屏也要问「默认启动页」，
// 两处必须长得一样，所以提取成公共组件，避免抄两份、以后改一处漏一处。
package com.shuaati.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 一个单选行。
 *
 * @param title 选项文字
 * @param selected 是否当前选中
 * @param onClick 点整行时回调（圆点本身不单独响应，避免二次触发）
 */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = appColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = AppText.body,
            color = c.textPrimary,
        )
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = c.primary,
                unselectedColor = c.textSecondary,
            ),
        )
    }
}
