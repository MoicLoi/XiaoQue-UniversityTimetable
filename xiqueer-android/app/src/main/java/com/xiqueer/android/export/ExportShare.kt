package com.xiqueer.android.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把导出的字节丢进 `cacheDir/export/`,再用系统分享面板发出去。
 *
 * 为什么不写进「下载」目录:那需要 `WRITE_EXTERNAL_STORAGE`(API 29 起还要
 * `MediaStore` 或 `MANAGE_EXTERNAL_STORAGE`),对一个"导出课表"来说权限太重。
 * 放 cache 目录 + `FileProvider` 分享,用户选"保存到文件"就落到他想放的地方,
 * 我们一个存储权限都不用要。
 *
 * ⚠️ 文件名会进 `FileProvider`,必须避免路径分隔符 —— 这里统一做白名单替换。
 */
object ExportShare {

    /** 生成结果 + 建议的 MIME 与文件名。 */
    data class Artifact(val bytes: ByteArray, val fileName: String, val mime: String)

    fun share(context: Context, artifact: Artifact) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, safeName(artifact.fileName))
        file.writeBytes(artifact.bytes)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val send = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, artifact.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "导出课表").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 只留字母/数字/`.`/`_`/`-`,其余替换成 `_`。
     *
     * 注意 `Character.isLetterOrDigit` 对中文返回 **true**,所以「课表.xlsx」原样保留 ——
     * 这条规则真正拦掉的是路径分隔符与 `..`,防止文件名被用来穿越目录。
     */
    private fun safeName(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .ifEmpty { "export" }

    fun csvArtifact(t: com.xiqueer.protocol.Timetable, week: Int) = Artifact(
        bytes = TimetableExport.csv(t),
        fileName = "课表_第${week}周.csv",
        mime = "text/csv",
    )

    fun xlsxArtifact(t: com.xiqueer.protocol.Timetable, times: com.xiqueer.android.data.PeriodTimes) =
        Artifact(
            bytes = TimetableExport.xlsx(t, times),
            fileName = "课表.xlsx",
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )

    /**
     * 一张可以直接发群里的课表图。
     *
     * 也接 [shifts] —— 导出图和屏幕上看到的必须是同一份数据,
     * 否则会出现"截图里有调休、导出图里没有"这种最尴尬的不一致。
     */
    fun pngArtifact(
        t: com.xiqueer.protocol.Timetable,
        times: com.xiqueer.android.data.PeriodTimes,
        shifts: List<com.xiqueer.android.data.Shift>,
        week: Int,
    ) = Artifact(
        bytes = TimetableImage.png(t, times, shifts),
        fileName = "课表_第${week}周.png",
        mime = "image/png",
    )

    /**
     * 日历事件。**没有作息表就返回 null** —— 见 [TimetableExport.ics]。
     * 这里不接收"第几周周一"之类的参数:时间基准只能由课表模型自己给,
     * 从外面传一个近似值进来正是之前那个"整体晚 3 周" bug 的来源。
     */
    fun icsArtifact(
        t: com.xiqueer.protocol.Timetable,
        times: com.xiqueer.android.data.PeriodTimes,
    ): Artifact? {
        val text = TimetableExport.ics(t, times) ?: return null
        return Artifact(
            bytes = text.toByteArray(Charsets.UTF_8),
            fileName = "课表.ics",
            mime = "text/calendar",
        )
    }
}
