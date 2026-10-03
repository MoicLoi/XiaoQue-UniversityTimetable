// 喜鹊儿协议核心 —— 纯 JVM Kotlin 库。
//
// 设计约束(见 ../SPEC.md §11):
//   * 主源集**零运行时依赖**,只用 java.security / javax.crypto / java.util.Base64。
//   * 不依赖任何 Android API,因此可以纯 JVM 跑向量单测,不需要设备。
//   * 所有联网由外部注入的 XqTransport 完成,本模块不碰 socket。
plugins {
    kotlin("jvm") version "2.4.10"
}

group = "com.xiqueer"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Gson 仅用于读取 vectors.json,不进主源集
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}

// 把向量打进测试资源,测试就不依赖工作目录了
sourceSets {
    test {
        resources.srcDir("../vectors")
    }
}

// ⚠️ 内置的 `test` 任务在本仓库**不可用**。
//
// 工程路径含中文(`...\Desktop\喜鹊儿cli\...`)时,Gradle 的 test worker 会
// `ClassNotFoundException: com.xiqueer.protocol.VectorConformanceTest`,而同一份工程
// 通过 junction 映射到 ASCII 路径(`C:\xqascii`)就能正常加载 —— 已实测确认。
// 这是 Gradle 在 Windows 非 ASCII 路径下的 worker classpath 问题,不是测试代码的问题。
//
// 所以一致性验收走下面的 `vectors`(JavaExec 直接跑 JUnitCore,不经 worker)。
// `test` 保持关闭,避免 `gradlew build` 因此失败。在 IDEA 里可以右键单独跑测试类。
tasks.test {
    enabled = false
}

val vectors by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "协议一致性向量:与 protocol/js 参考实现逐字节比对(不经 Gradle test worker)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.junit.runner.JUnitCore")
    // ⚠️ 这里**不要**写死类名。原来只写了 VectorConformanceTest,
    // 于是新加的测试类会一声不响地不执行 —— 而且输出仍然是 "OK (N tests)",
    // 看起来一切正常。改成所有 *Test 类都跑。
    args(*sourceSets["test"].allSource.files
        .filter { it.name.endsWith("Test.kt") }
        .map { "com.xiqueer.protocol." + it.nameWithoutExtension }
        .sorted()
        .toTypedArray())
    workingDir = projectDir
    standardOutput = System.out
    errorOutput = System.err
}

tasks.named("check") {
    dependsOn(vectors)
}
