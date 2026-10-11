plugins {
    id("min.agent.jvm")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":agent:spi"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
