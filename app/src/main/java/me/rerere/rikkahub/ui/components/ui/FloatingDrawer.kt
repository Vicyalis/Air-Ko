package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮侧栏（VS Code 式浮层）。
 *
 * 内容和面板是两层：对话界面始终满宽，侧栏是一块浮在上面的圆角面板，
 * 带跟随淡出的半透明遮罩。点遮罩、在面板上向左拖拽或按返回键都能收起；
 * 收起后在内容界面向右拖拽也能把面板拉出来（菜单按钮依然可用）。
 * 面板开合状态由调用方维护（[onOpenChange]）。
 *
 * [open] 由调用方维护为单一事实来源，组件内部只做跟随动画。
 */
@Composable
fun FloatingDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    scrimColor: Color = Color.Black,
    scrimOpacity: Float = 0.32f,
    /** 面板四周让开的区域：默认整机安全区；页内嵌（顶栏之下）时可换成 Scaffold 的 innerPadding */
    panelInsets: PaddingValues = WindowInsets.safeDrawing.asPaddingValues(),
    drawer: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val startPadding = 10.dp
    val startPaddingPx = with(density) { startPadding.toPx() }
    val panelWidthPx = with(density) { panelWidth.toPx() }
    val shadowMarginPx = with(density) { 48.dp.toPx() }
    // 面板完全滑出屏幕所需位移：面板宽 + 左间距 + 阴影余量
    val hiddenOffset = -(panelWidthPx + startPaddingPx + shadowMarginPx)

    val offset = remember { Animatable(if (open) 0f else hiddenOffset) }
    val scope = rememberCoroutineScope()

    // 收起状态下在内容界面向右拖拽：把面板拉出来（面板自身左滑/点遮罩/返回键仍负责收起）
    val pullOutState = rememberDraggableState { delta ->
        if (delta > 0f || offset.value > hiddenOffset) {
            scope.launch {
                offset.snapTo((offset.value + delta).coerceIn(hiddenOffset, 0f))
            }
        }
    }

    LaunchedEffect(open, hiddenOffset) {
        if (open) {
            offset.animateTo(0f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
        } else {
            offset.animateTo(hiddenOffset, tween(durationMillis = 240, easing = FastOutLinearInEasing))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .draggable(
                orientation = Orientation.Horizontal,
                state = pullOutState,
                enabled = !open,
                onDragStopped = { velocity ->
                    if (!open) {
                        if (velocity > 650f || offset.value > hiddenOffset * 0.5f) {
                            scope.launch { offset.animateTo(0f, tween(200)) }
                            onOpenChange(true)
                        } else {
                            scope.launch { offset.animateTo(hiddenOffset, tween(200)) }
                        }
                    }
                },
            ),
    ) {
        content()

        // 遮罩：透明度跟住面板位移；打开时点击关闭并挡住底层内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = scrimOpacity * ((offset.value - hiddenOffset) / -hiddenOffset).coerceIn(0f, 1f)
                }
                .then(
                    if (open) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenChange(false) }
                    } else {
                        Modifier
                    }
                )
                .background(scrimColor),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(
                    start = startPadding,
                    top = panelInsets.calculateTopPadding() + 6.dp,
                    bottom = panelInsets.calculateBottomPadding() + 6.dp,
                )
                .width(panelWidth)
                .fillMaxHeight()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offset.snapTo((offset.value + delta).coerceIn(hiddenOffset, 0f))
                        }
                    },
                    onDragStopped = { velocity ->
                        if (velocity < -650f || offset.value < hiddenOffset * 0.5f) {
                            // 左甩或拖过一半：先动画面板滑出，再让上层把状态置为收起
                            scope.launch { offset.animateTo(hiddenOffset, tween(200)) }
                            onOpenChange(false)
                        } else {
                            // 没到阈值：弹回去
                            scope.launch { offset.animateTo(0f, tween(200)) }
                        }
                    },
                ),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
        ) {
            drawer()
        }
    }
}
