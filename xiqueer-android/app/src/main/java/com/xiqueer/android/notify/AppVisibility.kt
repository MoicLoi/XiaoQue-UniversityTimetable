package com.xiqueer.android.notify

/**
 * App 是否在前台。
 *
 * 用途只有一个:会话失效时**要不要弹通知**。
 *
 * - 前台 → 不弹。用户正看着界面,顶部提示条已经说清楚了,再弹一条是噪音;
 * - 后台 → 弹。用户不在看,这条通知是他唯一的知情渠道,
 *   没有它就会重演"打开 App 发现一屏空白、还不知道为什么"。
 *
 * 用 `@Volatile` 而不是 LiveData/Flow:它只被读一次、没有任何订阅语义,
 * 引一套生命周期观察者反而更重。**由 Activity 的 onStart/onStop 维护**。
 */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
