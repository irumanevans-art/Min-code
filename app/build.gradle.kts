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
        versionCode = 32
        versionName = "2.0.1"

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
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
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
    implementation(libs.androidx.appcompat)
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
    "baselineProfile"(project(":baselineprofile"))
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

baselineProfile {
    // 生成到 app/src/main，release/debug 都能吃到
    automaticGenerationDuringBuild = false
    saveInSrc = true
}
