package com.xiqueer.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 Baseline Profile。
 *
 * 录进去的必须是**真实的高频路径**,不能只 startActivityAndWait 一下就完事:
 * profile 里没有的方法,系统就不会为它做 AOT 编译,启动该慢还是慢。
 *
 * 所以这里走一遍:冷启动 → 等课表 → 切到成绩 → 回到课表 → 展开助手面板。
 * 这几步覆盖了协议层解析(XqJson/XqFeatures)、Compose 首帧、玻璃层绘制、
 * 以及 agent 面板的构建 —— 全是启动后立刻要用的东西。
 *
 * 跑法(设备上会真启动 App,跑完把 profile 拉出来):
 *   ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.xiqueer.macrobenchmark.BaselineProfileGenerator
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val pkg = "com.xiqueer.android"

    @Test
    fun generate() = rule.collect(
        packageName = pkg,
        // 迭代要够多:profile 是"多次采样的并集",一轮很容易漏掉慢启动里的分支
        maxIterations = 12,
        stableIterations = 3,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        val device = androidx.test.uiautomator.UiDevice
            .getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        // 等首帧之后的异步内容(课表缓存解析 / 网络刷新)
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 3_000)

        // 底部导航:成绩 → 回课表。这两下会把各页的 Compose 代码路径录进来
        tapBottomTab(device, index = 1)
        device.waitForIdle()
        tapBottomTab(device, index = 0)
        device.waitForIdle()
    }

    /** 底部导航 5 个等分按钮,按索引点中间那个 x。 */
    private fun tapBottomTab(device: androidx.test.uiautomator.UiDevice, index: Int) {
        val w = device.displayWidth
        val y = (device.displayHeight * 0.955f).toInt()
        val x = ((index + 0.5f) / 5f * w).toInt()
        device.click(x, y)
    }
}
