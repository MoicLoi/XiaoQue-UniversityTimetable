package com.xiqueer.android.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 毛玻璃系统。
 *
 * 关键设计:**背景只模糊一次**。
 *
 * `Modifier.blur()` 模糊的是"它自己画的内容",不是"它背后的东西"。想做真正的
 * 磨砂玻璃,必须把背景录进一个 [GraphicsLayer],再让每个玻璃面**裁切后采样**
 * 那一份模糊结果。
 *
 * 所以:
 * - 背景自己正常绘制(**保持清晰**);
 * - 同一个背景同时被录进 [GlassSource.layer];
 * - 玻璃面按自己的屏幕位置反向平移后画这份层,于是"透过玻璃看到的是同一片背景"。
 *
 * 一次 `RenderEffect`,N 个玻璃面零额外开销 —— 这正是不在每个卡片上直接 blur 的原因。
 */

/** 背景采样源。整个界面共享一个。 */
class GlassSource internal constructor(internal val layer: GraphicsLayer) {
    internal var size by mutableStateOf(IntSize.Zero)
    internal var blurPx by mutableStateOf(-1f)

    /** 更新模糊半径(px)。API < 31 无 RenderEffect,会退化为纯半透明。 */
    internal fun setBlur(px: Float) {
        if (blurPx == px) return
        blurPx = px
        layer.renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && px > 0f) {
            BlurEffect(px, px, TileMode.Clamp)
        } else {
            null
        }
    }

    internal val ready: Boolean get() = size.width > 0 && size.height > 0
}

val LocalGlassSource = compositionLocalOf<GlassSource?> { null }

@Composable
fun rememberGlassSource(blurRadius: Dp = 24.dp): GlassSource {
    val density = LocalDensity.current
    // GraphicsLayer 只能由 rememberGraphicsLayer() 创建(构造函数需要 impl)
    val layer = rememberGraphicsLayer()
    val source = remember(layer) { GlassSource(layer) }
    remember(blurRadius, density, source) {
        source.setBlur(with(density) { blurRadius.toPx() })
    }
    return source
}

/**
 * 背景层:既照常**清晰绘制**,又把自己录进 [source] 的采样层。
 *
 * 只包背景,**绝对不要把内容也包进来** —— 采样层里若有玻璃面自己,
 * 就会自我反馈(模糊的模糊)。
 *
 * 用法:
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     GlassBackground(source) { Box(Modifier.fillMaxSize().background(brush)) }
 *     AppContent()          // 玻璃面在这里面,采样的是上面那层背景
 * }
 * ```
 */
@Composable
fun GlassBackground(
    source: GlassSource,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .onGloballyPositioned { source.size = it.size }
            .drawWithContent {
                // record 留一份清晰副本供玻璃面采样;drawContent 让背景本身保持锐利
                if (source.ready) source.layer.record { this@drawWithContent.drawContent() }
                drawContent()
            },
        content = content,
    )
}

/**
 * 一块玻璃面。
 *
 * API 31+ 会真的模糊背后的背景;更低版本自动退化为「半透明 + 描边」,
 * 观感仍然成立(这也是设计上要求有兜底的原因)。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = GlassTokens.Fill,
    stroke: Color = GlassTokens.Stroke,
    strokeWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = LocalGlassSource.current
    var topLeft by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .onGloballyPositioned { topLeft = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                val s = source
                if (s != null && s.ready && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // 反向平移:让背景在玻璃下保持与屏幕一致,而不是跟着控件走
                    translate(-topLeft.x, -topLeft.y) { drawLayer(s.layer) }
                }
                drawRect(tint)
            }
            .border(strokeWidth, stroke, shape)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** 玻璃设计令牌 —— 与 DESIGN.md §5.1 对齐。 */
object GlassTokens {
    val Fill = Color(0x14FFFFFF)          // 8%
    val FillStrong = Color(0x24FFFFFF)    // 14%
    val Stroke = Color(0x26FFFFFF)        // 15%
    val StrokeAccent = Color(0x8C6FA8FF)
    val Shadow = Color(0x40000000)
}

/**
 * 底部玻璃浮层(scrim + 玻璃面板)。
 *
 * 用**同窗口内**的浮层,而不是 `ModalBottomSheet` —— 后者是独立 Window,
 * 采样不到主窗口录制的背景层,玻璃会失效。
 *
 * 注意顺序:scrim 先画,玻璃面采样的仍是 scrim **之前**的背景,观感才对。
 */
@Composable
fun BoxScope.GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C05070B))
            .clickable(interactionSource = interaction, indication = null) { onDismiss() },
    )
    // 面板自己吃掉落在它内部的手势。
    //
    // ⚠️ 这不是洁癖,是真机实测到的问题:没有这一层,面板里**任何非可点的地方**
    // ——说明文字、输入框四周那圈 padding、标题——点下去都会穿透到底下那层
    // 全屏遮罩,于是"想点输入框"变成"把整个面板关了"。
    // 输入框尤其明显:视觉上的输入框包含 padding,而 BasicTextField 的可点区域
    // 只有文字那一块,所以贴着边点必然穿透。
    //
    // 顺序要紧:`.padding()` 在前、`.clickable` 在后 —— 这样面板四周那 12/20dp
    // 的留白仍然属于遮罩(点它=关闭),只有面板本体消费手势。
    val panelInteraction = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 20.dp)
            .clickable(interactionSource = panelInteraction, indication = null) { },
        shape = RoundedCornerShape(24.dp),
        tint = GlassTokens.FillStrong,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
