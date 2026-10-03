// P3a 的验证与 profile 生成模块。
//
// 这是 `com.android.test` 模块:**它自己会被装到设备上**,再驱动被测 app。
// 所以它不能跟 app 共用依赖,也不能被打进 app 的包体。
//
// 同样不应用 org.jetbrains.kotlin.android —— AGP 9 内置 Kotlin 支持。
plugins {
    id("com.android.test")
}

android {
    namespace = "com.xiqueer.macrobenchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Macrobenchmark 要求 API 23+;被测 app 是 26,这里跟着来
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /**
     * 被测目标是 :app 的 `benchmark` 变体 —— R8 跑过、debug key 签名、声明了 profileable。
     * 这个模块自己的 build type 名字必须和被测变体对齐,否则 AGP 找不到要装哪个包。
     */
    targetProjectPath = ":app"

    // 自己给自己插桩(Macrobenchmark 的标准配置)
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            // macrobenchmark 模块本身只是个测试壳,用 debug key 签就行
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // 测试代码放在 src/main —— self-instrumenting 模块不是普通的 androidTest
    sourceSets["main"].java.srcDir("src/main/java")
    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test:core:1.5.0")
    // 用来把 app 点回"已登录 + 课表"这种真实状态,否则只能量到登录页
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
    implementation("junit:junit:4.13.2")
}
