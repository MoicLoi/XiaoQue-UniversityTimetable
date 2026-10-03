package com.xiqueer.android.repository

import android.content.Context
import com.xiqueer.android.data.CacheStore
import com.xiqueer.protocol.Timetable
import com.xiqueer.protocol.XqFeatures
import com.xiqueer.protocol.XqJson

/**
 * 不依赖 ViewModel 的课表缓存读取。
 *
 * 闹钟接收器、Worker 这些后台入口拿不到 ViewModel,但都需要课表 ——
 * 统一从这里读,保证「当前学期」永远有一份可直接用的缓存。
 */
object TimetableCache {

    /** 当前学期的课表缓存键。刷新时会同时写这个键,后台入口只认它。 */
    const val KEY_CURRENT = "timetable_current"

    /** 与 [KEY_CURRENT] 配套的学期代码 —— 缓存的 JSON 里没有这个信息。 */
    const val KEY_CURRENT_TERM = "timetable_current_term"

    fun read(context: Context): Timetable? {
        val text = CacheStore(context).read(KEY_CURRENT) ?: return null
        return runCatching { XqFeatures.parseTimetable(XqJson.parseObject(text), "") }.getOrNull()
    }

    fun savedAt(context: Context): Long = CacheStore(context).savedAt(KEY_CURRENT)
}
