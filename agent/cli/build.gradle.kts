plugins {
    id("min.agent.jvm")
    application
}

dependencies {
    implementation(project(":agent:kernel"))
}

application {
    mainClass.set("dev.min.agent.cli.MainKt")
}
