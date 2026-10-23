plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.detekt)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.churchpresenter.signage.agent.MainKt")
    applicationName = "churchpresenter-signage"
}

// AGENT_VERSION, from the build's version, so the agent says what it was released as.
val generateVersion by tasks.registering {
    val version = project.version.toString()
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("org/churchpresenter/signage/agent/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package org.churchpresenter.signage.agent\n\n" +
                "/** This agent's version, set by the build. */\n" +
                "const val AGENT_VERSION = \"$version\"\n",
        )
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateVersion)
}

dependencies {
    implementation(projects.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.jmdns)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    detektPlugins(libs.detekt.formatting)
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

tasks.test {
    useJUnitPlatform()
}
