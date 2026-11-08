// 通用组件：小统计块（一个数字 + 一个标签），首页四宫格用它
package com.shuaati.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/**
 * 小统计块：上面大数字，下面一行标签。
 *
 * @param value 数字/文字主体（例如「128」「68%」「—」）
 * @param label 标签（例如「累计做题」）
 * @param valueColor 数字颜色，默认主文字色；正确率、错题数可以传语义色
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val c = appColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Radius.cardShape,
        color = c.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text(
                text = value,
                style = AppText.heading,
                color = valueColor ?: c.textPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = label,
                style = AppText.caption,
                color = c.textSecondary,
            )
        }
    }
}
