plugins {
    id("min.agent.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agent:protocol"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
