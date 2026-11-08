// 通用组件：按钮（主按钮 / 次按钮 / 文字按钮）
//
// 统一圆角 12dp、最小高度 48dp；三种样式只是颜色不同，尺寸与手感完全一致。
package com.shuaati.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shuaati.app.ui.theme.AppText
import com.shuaati.app.ui.theme.ComponentSize
import com.shuaati.app.ui.theme.Radius
import com.shuaati.app.ui.theme.Spacing
import com.shuaati.app.ui.theme.appColors

/** 按钮样式 */
enum class AppButtonStyle {
    /** 实心主色：一个页面里最重要的一步（确认、下一题、开始考试） */
    PRIMARY,

    /** 描边：次要动作（上一题、返回主页） */
    SECONDARY,

    /** 纯文字：轻量动作（返回、切换、全部开始） */
    TEXT,
}

/**
 * 统一按钮。
 *
 * @param text 按钮文字
 * @param onClick 点击回调
 * @param style 三种样式，默认主按钮
 * @param enabled 是否可点；禁用时统一变灰，但高度不变，避免布局跳动
 * @param fillWidth 是否撑满宽度（默认撑满；顶栏里的「切换」之类传 false）
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.PRIMARY,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    val c = appColors()

    when (style) {
        AppButtonStyle.PRIMARY -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = ComponentSize.buttonMinHeight),
                shape = Radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.primary,
                    contentColor = c.onAccent,
                    disabledContainerColor = c.fill,
                    disabledContentColor = c.textSecondary,
                ),
            ) {
                Text(text = text, style = AppText.bodyStrong, color = if (enabled) c.onAccent else c.textSecondary)
            }
        }

        AppButtonStyle.SECONDARY -> {
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier
                    .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = ComponentSize.buttonMinHeight),
                shape = Radius.buttonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = c.primary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = c.textSecondary,
                ),
                border = BorderStroke(
                    ComponentSize.stroke,
                    if (enabled) c.primary else c.border,
                ),
            ) {
                Text(
                    text = text,
                    style = AppText.bodyStrong,
                    color = if (enabled) c.primary else c.textSecondary,
                )
            }
        }

        AppButtonStyle.TEXT -> {
            TextButton(
                onClick = onClick,
                enabled = enabled,
                modifier = modifier.defaultMinSize(minHeight = ComponentSize.buttonMinHeight),
            ) {
                Text(
                    text = text,
                    style = AppText.bodyStrong,
                    color = if (enabled) c.primary else c.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
        }
    }
}
