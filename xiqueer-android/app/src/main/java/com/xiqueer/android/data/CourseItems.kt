package com.xiqueer.android.data

import android.content.Context

/**
 * 每门课的「上课要带什么」。
 *
 * **由用户手写,机器不猜** —— 教材/白大褂/计算器这种东西没有任何接口能告诉我们,
 * 猜出来的错误提示比不提示更糟(学生会因此被扣分)。
 *
 * 以**课程名**为键,不是以某一次课为键:同一门课不同周次带的东西是一样的,
 * 而按"课程名"建键才能让设置页只列一份清单。
 */
class CourseItemsStore(context: Context) {

    private val prefs = context.getSharedPreferences("course_items", Context.MODE_PRIVATE)

    fun get(courseName: String): String =
        prefs.getString(courseName, "").orEmpty()

    fun set(courseName: String, items: String) {
        val v = items.trim()
        prefs.edit().apply {
            if (v.isEmpty()) remove(courseName) else putString(courseName, v)
        }.apply()
    }

    /** 全部已填的(用于设置页展示与「哪些课还没填」提示)。 */
    fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
}
