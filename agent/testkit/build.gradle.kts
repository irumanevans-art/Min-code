plugins {
    id("min.agent.jvm")
}

dependencies {
    api(project(":agent:kernel"))
    api(project(":agent:llm"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
