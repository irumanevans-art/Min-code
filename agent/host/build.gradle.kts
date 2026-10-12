plugins {
    id("min.agent.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agent:spi"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":agent:testkit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jimfs)
}
