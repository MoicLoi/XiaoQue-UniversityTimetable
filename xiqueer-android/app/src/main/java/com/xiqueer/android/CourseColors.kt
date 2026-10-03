package com.xiqueer.android

/**
 * 按课程名稳定取色的调色板。
 *
 * 只放**一处**:课表网格页要用它画格子,导出图片也要用同一套颜色 ——
 * 两处各写一份的话,导出的图和屏幕上看到的会对不上色。
 *
 * 返回 `Int`(ARGB)而不是 `androidx.compose.ui.graphics.Color`:
 * 导出图片走的是原生 `Canvas`/`Paint`,拿不到 Compose 的类型。
 * 用 `Int` 当公共表示,两边各自包一层即可。
 */
object CourseColors {

    private val HUES = intArrayOf(
        0xFF4C7DF0.toInt(), 0xFF3FA796.toInt(), 0xFFB388EB.toInt(), 0xFFE08B5A.toInt(),
        0xFF5AA9E6.toInt(), 0xFF9BB13E.toInt(), 0xFFD06C8A.toInt(), 0xFF6C7BD0.toInt(),
    )

    /** 同一门课永远同一个色 —— 便于在一张网格里扫视。 */
    fun of(courseName: String): Int = HUES[Math.floorMod(courseName.hashCode(), HUES.size)]
}
