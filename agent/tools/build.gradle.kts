plugins {
    id("min.agent.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agent:spi"))
    implementation(project(":agent:policy"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
