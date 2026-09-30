plugins {
    id("min.agent.jvm")
    application
}

dependencies {
    implementation(project(":agent:kernel"))
    implementation(project(":agent:host"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.min.agent.cli.MainKt")
}
