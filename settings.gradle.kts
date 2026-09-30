pluginManagement {
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // 不挂 mavenLocal()：~/.m2 里的东西随手一个 `publishToMavenLocal` 就能变，
        // 而且它排在最前面会先于 google()/mavenCentral() 命中 —— 本机能编、别人机器上
        // 编不出来，或者更糟：两边编出来的不是同一个东西。本仓库没有任何依赖来自它
    }
}

rootProject.name = "min-code"
include(":app")
include(":highlight")
include(":workspace")
include(":baselineprofile")

// Min Agent 自研内核：纯 Kotlin/JVM 模块，依赖规则见 build-logic 的 AgentModuleRules 与 docs/agent/02-总体架构.md §2
include(":agent:protocol")
include(":agent:spi")
include(":agent:store")
include(":agent:llm")
include(":agent:policy")
include(":agent:context")
include(":agent:tools")
include(":agent:ext")
include(":agent:kernel")
include(":agent:testkit")
include(":agent:cli")
