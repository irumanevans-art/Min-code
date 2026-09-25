import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// 本地签名：仓库根目录 keystore.properties + secrets/*.jks（均已 gitignore）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.min.code"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.min.code"
        minSdk = 26
        targetSdk = 37
        versionCode = 57
        versionName = "2.1.24"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    testOptions {
        unitTests {
            // android.util.Log 在 JVM 单测里默认抛「not mocked」。核心逻辑里到处都有
            // Log.d/w，而它们又常常待在 runCatching 里面 —— 一抛就被就地吞掉，表现是
            // 那条代码路径"什么也没发生"，测试挂在等待上，看不出真正原因。
            // 返回默认值，让这些分支能进测试。
            isReturnDefaultValues = true
        }
        // ProviderPresetTest 直接读 `src/main/assets/provider_presets.json` 那份真文件
        // （它是纯数据，编译器一个字都不检查，漏个逗号只会表现为「预设列表空了」）。
        // 但那是一次普通的文件读取，Gradle 不知道它是测试的输入 —— 不声明的话，
        // 改完表跑测试会直接 UP-TO-DATE，保险丝形同虚设。
        unitTests.all {
            it.inputs
                .files(fileTree("src/main/assets"))
                .withPropertyName("mainAssets")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    splits {
        abi {
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        val storePath = keystoreProperties.getProperty("storeFile")
        val storePass = keystoreProperties.getProperty("storePassword")
        val alias = keystoreProperties.getProperty("keyAlias")
        val keyPass = keystoreProperties.getProperty("keyPassword")
        if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
            !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
        ) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // R8 故意关着（proguard-rules.pro 因此是空的）：壳进程按类名经 app_process 拉起
            // PrivilegedServer、里面还有反射，Koin / kotlinx.serialization / AIDL 也都靠名字 ——
            // 一改名就是运行时才炸，编译期看不出来。要开得先补齐 keep 规则，再真机把主路径全过一遍
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // 和正式包分开装：调试版是独立的一份数据（rootfs 占地几个 GB，别互相覆盖）
            applicationIdSuffix = ".debug"
        }
        // baselineprofile 采集用的非 debuggable 变体；安装时 ART 会吃 baseline.prof
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            // 采集用，跟 release 同一把钥匙（有的话）
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
        resources {
            pickFirsts += "META-INF/LICENSE.md"
            pickFirsts += "META-INF/LICENSE.txt"
            pickFirsts += "META-INF/LICENSE"
            pickFirsts += "META-INF/NOTICE.md"
            pickFirsts += "META-INF/NOTICE.txt"
            pickFirsts += "META-INF/NOTICE"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

dependencies {
    implementation(project(":highlight"))
    implementation(project(":workspace"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Theme.Min 的 parent 是 Theme.Material3.DayNight.NoActionBar，来自这个（View 体系的）
    // material 库。之前它是 :workspace 的 implementation —— 编译期不传递，但 AAR 资源会，
    // 于是 app 靠一个 proot 模块的实现细节才编得出主题。放回真正用它的人这边
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jetbrains.markdown)
    implementation(libs.huge.icons)
    implementation(libs.termux.terminal.view)
    implementation(libs.chrisbanes.haze)
    implementation(libs.androidx.profileinstaller)
    // 本机无线调试拉起 shell-uid 的 PrivilegedServer，不依赖另装 Shizuku。
    // ADB 协议/TLS/配对代码移植进 io.github.muntashirakon.adb（源自 libadb-android，Apache-2.0，去掉了 Conscrypt）。
    // bcpkix 供配对加密与自签证书；spake2-android 带 BoringSSL 的 libspake2.so，必须和 adbd 同实现才能配上。
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.spake2)
    "baselineProfile"(project(":baselineprofile"))
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

baselineProfile {
    // saveInSrc 把产物按变体写到 app/src/release/generated/baselineProfiles，
    // 只有 release 打包消费；debug 变体不吃 profile，量启动收益要装 release 包
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

// 双份 CHANGELOG（根目录 + 关于页读的 assets 副本）必须逐字节一致。
// 以前靠人工约定同步，谁忘了一次关于页就展示旧日志 —— 现在构建期拦住。
val verifyChangelogSync by tasks.registering {
    val rootChangelog = layout.projectDirectory.file("../CHANGELOG.md")
    val assetChangelog = layout.projectDirectory.file("src/main/assets/CHANGELOG.md")
    val stamp = layout.buildDirectory.file("changelog-sync/ok.stamp")
    inputs.files(rootChangelog, assetChangelog)
    outputs.file(stamp)
    doLast {
        val same = rootChangelog.asFile.readBytes().contentEquals(assetChangelog.asFile.readBytes())
        if (!same) {
            throw GradleException(
                "两份 CHANGELOG 不一致：根目录 CHANGELOG.md 与 app/src/main/assets/CHANGELOG.md " +
                    "必须同步更新（关于页读 assets 那份，见 CLAUDE.md 的工作方式一节）",
            )
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok")
    }
}
tasks.named("preBuild") { dependsOn(verifyChangelogSync) }
