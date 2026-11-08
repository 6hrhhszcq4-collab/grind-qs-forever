// 设计令牌：动效（时长 / 缓动 / 弹簧 / 系统「移除动画」开关）
//
// 规矩（与 Dimen.kt 一致）：页面里不写 tween(250) 这种随手值，
// 一律用 Motion.FAST / Motion.NORMAL / Motion.SLOW + motionTween(...) / motionSpring()。
//
// 为什么要绕一层 motionTween：
//   Compose 的 tween 不会自己读系统设置。系统「移除动画」（动画时长比例 = 0）时，
//   系统自己那套动画会瞬间完成，而我们的 tween 还会照播 —— 这就是「关掉动画还在动」。
//   所以这里统一读 Settings.Global.ANIMATOR_DURATION_SCALE：
//     - 比例 = 0（关动画）→ 返回 snap()，一帧到位；
//     - 比例 > 0（含 0.5x / 1.5x）→ 时长按比例缩放，跟系统行为一致。
//
// 只用 Compose 自带的 animation / animation-core，不引任何第三方动画库。
package com.shuaati.app.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

/**
 * 动效令牌：全工程唯一的「时间和幅度」出处。
 *
 * 时长三档（任务 07A 规定）：
 * - [FAST] 150ms：按压、图标缩放这类「手指跟得上」的反馈；
 * - [NORMAL] 250ms：Tab 切换、颜色变化、切题淡入淡出；
 * - [SLOW] 600ms：环形进度增长、分数滚动这类「让眼睛看清数字在变」的动画。
 *
 * 交互反馈统一用 [spring]，不写死时长 —— 弹簧跟手，不会有「卡一下」的迟滞感。
 * 注意：答对/答错这一组的**总时长必须 ≤ 300ms**（350ms 后要自动跳题），
 * 所以它们的弹跳/抖动单独用短时长常量，不套用 [SLOW]。
 */
object Motion {
    /** 150ms：按压、Tab 图标缩放、颜色微调 */
    const val FAST = 150

    /** 250ms：Tab 切换、切题淡入淡出、选项状态变色 */
    const val NORMAL = 250

    /** 600ms：环形进度增长、交卷分数滚动 */
    const val SLOW = 600

    /** 统一缓动：先快后慢，比线性自然 */
    val easing: Easing = FastOutSlowInEasing

    /** 手指按下时选项的缩放（答完松手回 1.0） */
    const val PRESS_SCALE = 0.97f

    /** 底部导航选中图标的缩放 */
    const val TAB_ICON_SCALE = 1.12f

    /** 答对时正确项弹一下的峰值缩放 */
    const val CORRECT_POP_SCALE = 1.03f

    /** 答对弹跳的每段时长（放大 120ms + 回弹 120ms = 240ms，给 350ms 跳题留 110ms 余量） */
    const val CORRECT_POP_STEP = 120

    /** 答错抖动：最大摆幅 6dp、回摆 4dp、总时长 200ms */
    const val SHAKE_DP = 6f
    const val SHAKE_SOFT_DP = 4f
    const val SHAKE_DURATION = 200

    /** 引导页进度点选中时的放大倍数 */
    const val DOT_SCALE = 1.4f

    /** 引导页切屏的滑动比例：只滑 1/6 屏宽，靠淡入淡出补足，避免「摔进来」 */
    const val ONBOARD_SLIDE_DIVISOR = 6

    /** 统一切题淡入淡出的两段时长（130 + 120 ≈ 250ms） */
    const val QUIZ_FADE_IN = 130
    const val QUIZ_FADE_OUT = 120

    /**
     * 页面左右移动（07A 修订，用户要求）：
     * 旧内容整体向左移出一屏、新内容从右侧一屏处移入，位移就是「整整一屏宽」。
     * 300ms 比淡入淡出那 250ms 稍长一点：一屏的位移距离大，太快会显得甩。
     */
    const val SLIDE = 300
}

/** 系统「动画时长比例」：0 表示用户在系统设置里把动画关掉了（或开发者选项设 0） */
private val LocalAnimatorDurationScale = staticCompositionLocalOf { 1f }

/**
 * 动效总开关：系统关掉动画时为 false。
 * 用法：`if (!LocalMotionEnabled) { 直接跳到终态 }`，或者干脆用下面的 motionTween / motionSpring。
 */
val LocalMotionEnabled: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalAnimatorDurationScale.current > 0f

/** [LocalMotionEnabled] 的函数写法，给「在 if 里用」的场景 */
@Composable
@ReadOnlyComposable
fun motionEnabled(): Boolean = LocalMotionEnabled

/**
 * 把令牌时长换算成实际毫秒数：系统关动画时返回 0，否则就是令牌原值。
 *
 * 这里**故意不乘系统比例**。原因：Compose 从 1.2 起自带 `MotionDurationScale`，
 * 它读的就是同一个 `animator_duration_scale`，动画时钟已经按这个比例缩放了。
 * 如果这里再乘一遍，开发者选项设 2x 会变成 4x、设 10x 会变成 100x（实测卡成静止）。
 * 所以这里只负责「关没关」这一件事，比例交给 Compose。
 */
@Composable
@ReadOnlyComposable
fun motionMillis(baseMillis: Int): Int {
    if (LocalAnimatorDurationScale.current <= 0f) return 0
    return baseMillis
}

/**
 * 带令牌缓动的 tween；系统关动画时退化成 snap（一帧到位，功能照用）。
 * 泛型 T 是动画值的类型：Float / Int / Color / IntOffset 都有对应的转换器。
 *
 * ⚠️ 这里的 remember 不是优化，是**稳妥起见必须的**：
 * `tween(...)` 每次调用都会 new 一个 TweenSpec，而 Compose 的 animateXxxAsState 会把
 * animationSpec 当作「要不要重新开始动画」的判定条件之一；每次重组都给新对象的话，
 * 动画有每帧从头重启的风险。用 remember 按「开没开 + 令牌时长 + 缓动」缓存同一个实例，
 * 动画才会稳定跑完。
 */
@Composable
fun <T> motionTween(
    baseMillis: Int,
    easing: Easing = Motion.easing,
): FiniteAnimationSpec<T> {
    val enabled = LocalAnimatorDurationScale.current > 0f
    return remember(enabled, baseMillis, easing) {
        if (!enabled) {
            snap()
        } else {
            tween(durationMillis = baseMillis, easing = easing)
        }
    }
}

/**
 * 交互反馈用的弹簧（阻尼 0.7 / 刚度 400）：跟手、回弹不过冲。
 * 系统关动画时同样退化成 snap。
 *
 * 同样用 remember 缓存：理由与 [motionTween] 一致，避免弹簧每帧重启导致「按下去回不来」。
 */
@Composable
fun <T> motionSpring(): FiniteAnimationSpec<T> {
    val enabled = LocalAnimatorDurationScale.current > 0f
    return remember(enabled) {
        if (!enabled) {
            snap<T>()
        } else {
            spring<T>(dampingRatio = 0.7f, stiffness = 400f)
        }
    }
}

/**
 * 读系统动画时长比例。
 * 正常路径读 `Settings.Global.ANIMATOR_DURATION_SCALE`（读到 0 就是关动画）；
 * 万一读不到（个别 ROM 限制），退回 [ValueAnimator.areAnimatorsEnabled]。
 */
fun readSystemAnimatorScale(context: Context): Float {
    val value = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrNull()
    if (value != null) return value.coerceIn(0f, 10f)
    return if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
}

/**
 * 把上面读到的比例注入给整棵界面树。
 *
 * - 只在创建时读一次，之后每次 Activity 回到前台（ON_RESUME）再读一次：
 *   用户去系统设置改完动画比例、切回 App 就能立刻生效，不用杀进程重开。
 * - 放在 [ShuaatiTheme] 里包住所有页面，所以任何页面都能拿到同一个开关。
 */
@Composable
fun ProvideMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scale by remember(context) { mutableStateOf(readSystemAnimatorScale(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scale = readSystemAnimatorScale(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalAnimatorDurationScale provides scale, content = content)
}
