package com.xiqueer.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3a 的验收闸门:冷启动耗时。
 *
 * 三种 [CompilationMode] 分开测,是因为它们的结论完全不同:
 * - [CompilationMode.None]  —— 不预编译,最接近"刚装完/刚更新完"的首次启动;
 * - [CompilationMode.Partial] —— 只 AOT 热方法,**这就是 Baseline Profile 起作用的场景**;
 * - [CompilationMode.Full]  —— 全量 AOT,作为"理论上限"参照。
 *
 * 只看 Partial 的数字来判断 Baseline Profile 有没有用,别拿 Full 自我安慰。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val pkg = "com.xiqueer.android"

    @Test
    fun coldStartupNoCompilation() = coldStartup(CompilationMode.None())

    @Test
    fun coldStartupPartialCompilation() = coldStartup(CompilationMode.Partial())

    /**
     * 全量 AOT。设备上要先 `adb shell cmd package compile -m speed -f com.xiqueer.android`,
     * 否则这个模式量到的还是没编译过的包。
     */
    @Test
    fun coldStartupFullCompilation() = coldStartup(CompilationMode.Full())

    private fun coldStartup(mode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = pkg,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            // 冷启动要保证进程真的不在:force-stop 之后再等一等,
            // 否则 measureRepeated 会把它当成温启动,数字好看但没意义
            device.executeShellCommand("am force-stop $pkg")
        },
    ) {
        startActivityAndWait()
        // 等到课表页出现才算"启动完成" —— 只等首帧会把网络/解析的时间漏掉
        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 5_000)
    }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}
