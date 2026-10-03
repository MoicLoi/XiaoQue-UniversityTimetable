package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// P1:先立住配色与层级;毛玻璃(blur + 玻璃面令牌)是 P2 的事,
// 但背景已经做成渐变,方便后面直接在上面叠模糊层。
object XqColors {
    val Ink = Color(0xFF0B0E14)
    val InkSoft = Color(0xFF151A24)
    val Aurora1 = Color(0xFF2B4C7E)
    val Aurora2 = Color(0xFF3E2C6B)
    val Aurora3 = Color(0xFF1E5F5A)
    val Accent = Color(0xFF6FA8FF)
    val AccentSoft = Color(0xFF9CC4FF)
    /** 不可逆操作(如提交选课)的警示色 —— 刻意与强调色拉开,别让人误点。 */
    val Danger = Color(0xFFFF8A8A)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xB3F2F5FA)
    val TextTertiary = Color(0x80F2F5FA)
    val GlassFill = Color(0x14FFFFFF)
    val GlassStroke = Color(0x26FFFFFF)
}

@Composable
fun XqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = XqColors.Accent,
            onPrimary = XqColors.Ink,
            background = XqColors.Ink,
            onBackground = XqColors.TextPrimary,
            surface = XqColors.InkSoft,
            onSurface = XqColors.TextPrimary,
            surfaceVariant = XqColors.InkSoft,
            onSurfaceVariant = XqColors.TextSecondary,
            error = Color(0xFFFF8A80),
        ),
        content = content,
    )
}

/** 极光渐变底。P2 会在它上面叠玻璃面。 */
@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(XqColors.Ink, XqColors.Aurora2, XqColors.Aurora1, XqColors.Ink),
                ),
            ),
        content = content,
    )
}
