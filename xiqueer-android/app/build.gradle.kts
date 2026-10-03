// AGP 9 起 Kotlin 支持内置,**不能**再应用 org.jetbrains.kotlin.android
// (会报 "no longer required for Kotlin support since AGP 9.0")。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 发布签名:全部从**环境变量**读,仓库里不存密钥。
 *
 * | 变量 | 含义 | 缺省 |
 * |---|---|---|
 * | `XQ_KEYSTORE` | keystore 文件路径 | `%USERPROFILE%/.xiqueer/me.molaic.betterxqr.jks` |
 * | `XQ_KEYSTORE_PASSWORD` | store 密码 | 无(缺了就不签名) |
 * | `XQ_KEY_ALIAS` | 别名 | `me.molaic.betterxqr` |
 * | `XQ_KEY_PASSWORD` | 别名密码 | 同 store 密码 |
 *
 * **没有 keystore 时不会让构建失败**,只是产出一个未签名的 release 包并打一行警告 ——
 * 否则任何人在没配密钥的机器上连 `assembleRelease` 都跑不了(CI 里尤其烦)。
 * 生成方式见 README「发布签名」一节。
 */
val keystorePath: String = System.getenv("XQ_KEYSTORE")
    ?: "${System.getProperty("user.home")}/.xiqueer/me.molaic.betterxqr.jks"
val keystoreFile = file(keystorePath)
val storePasswordEnv: String? = System.getenv("XQ_KEYSTORE_PASSWORD")
val keyAliasEnv: String = System.getenv("XQ_KEY_ALIAS") ?: "me.molaic.betterxqr"
val keyPasswordEnv: String? = System.getenv("XQ_KEY_PASSWORD") ?: storePasswordEnv

val hasReleaseKey = keystoreFile.exists() && !storePasswordEnv.isNullOrBlank()

android {
    namespace = "com.xiqueer.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 包名与源码 namespace 分开:namespace 是 Kotlin 包名(改了要搬目录),
        // applicationId 才是"这个 App 在市场/系统里的身份"。
        applicationId = "me.molaic.betterxqr"
        // java.util.Base64 / java.time 都是 API 26+,协议层依赖它们
        minSdk = 26
        targetSdk = 37
        // 包名与版本号换成了正式线:**这是一个全新的 App**,
        // 与旧的 me.moiclathy.dism 数据不互通、可以并存安装。
        // 所以 versionCode 从 1 重新起算 —— 它只在这个包名内比较。
        //
        // versionCode 1 / 2 是**开发期内部测试包,从未对外发布**;V1.0.2 是首次公开发布。
        // 编号跳过的原因:内部包 V1.0.1 有一个登录缺陷 —— 登录的版本闸门是
        // **业务明文里的 `appver`**,而不是请求信封里的 `appinfo`,只改后者完全无效(实测)。
        // 成因与阈值见 XqRsa.APPVER 的注释。
        //
        // ⚠️ 从源码自建时,如果要覆盖安装已发布的包,**版本号必须 >= 它**,
        // 否则 Android 会以 INSTALL_FAILED_VERSION_DOWNGRADE 拒绝。
        versionCode = 3
        versionName = "mlOfficial-V1.0.2"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        /**
         * 基准测试专用变体:**和 release 一样经过 R8**,但用 debug key 签名、并声明 `profileable`,
         * 这样它才能装到测试机上、也才能被 Macrobenchmark / Baseline Profile 生成器采样。
         *
         * 为什么不能直接测 debug 包:debug 没跑 R8、还挂着 LeakCanary 与调试信息,
         * 启动耗时会明显偏高,拿它当基线就是在骗自己。
         */
        create("benchmark") {
            initWith(getByName("release"))
            // benchmark 是测试件,固定用 debug key 签名即可 ——
            // 它不需要、也不应该用发布密钥(那样等于把发布签名用到每次性能测试上)
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        // 协议层是 JVM 17 的字节码
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // DebugReceiver 靠 BuildConfig.DEBUG 关掉自己;AGP 8+ 需要显式开启
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// jvmTarget 由上面的 compileOptions(Java 17)驱动。
// 不要用 kotlin { jvmToolchain(17) } —— 机器上只有 JDK 21,会要求下载工具链。

if (!hasReleaseKey) {
    logger.warn(
        "⚠️  未配置发布签名(找不到 $keystorePath 或没设 XQ_KEYSTORE_PASSWORD)," +
            "release 将产出未签名包,装不上。生成方式见 README「发布签名」。",
    )
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // 不用 Compose BOM:显式钉版本可以直接命中 Gradle 缓存
    implementation("androidx.compose.ui:ui:1.11.2")
    implementation("androidx.compose.ui:ui-graphics:1.11.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.2")
    implementation("androidx.compose.foundation:foundation:1.11.2")
    implementation("androidx.compose.runtime:runtime:1.11.2")
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    /**
     * Baseline Profile 的落地器。
     *
     * 它在**后台线程**把打进 APK 的 `baseline-prof.txt` 交给系统编译,
     * 所以不会拖慢首帧;API 24–30 上由它自己完成安装,31+ 走系统的 profile 安装路径。
     * 用它而不是 `compileSdk` 里的 ProfileInstaller 手写调用,是为了少一处启动路径上的代码。
     */
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.11.2")

    // 泄漏检测:只在 debug。发布版绝不引入 —— 它会拖慢启动并增加包体。
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
