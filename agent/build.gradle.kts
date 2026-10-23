plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sentry)
    application
}

// Uploads the sources so a stack trace in Sentry shows its code: only with SENTRY_AUTH_TOKEN, as in
// ChurchPresenter's own build, so a build without the secret is unchanged.
sentry {
    // Blank as well as unset: a workflow passes an empty string for a secret that is not set.
    val authToken = System.getenv("SENTRY_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
    includeSourceContext.set(authToken != null)
    org.set("church-projector")
    projectName.set("church-presenter-signage")
    this.authToken.set(authToken)
    autoInstallation.enabled.set(false)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.churchpresenter.signage.agent.MainKt")
    applicationName = "churchpresenter-signage"
}

// AGENT_VERSION, from the build's version, so the agent says what it was released as, and whether
// this is a release build (the Build workflow passes -PsignageVersion), whose crash reports are production's.
val generateVersion by tasks.registering {
    val version = project.version.toString()
    val release = providers.gradleProperty("signageVersion").isPresent
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    inputs.property("release", release)
    outputs.dir(out)
    doLast {
        val file = out.get().file("org/churchpresenter/signage/agent/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package org.churchpresenter.signage.agent\n\n" +
                "/** This agent's version, set by the build. */\n" +
                "const val AGENT_VERSION = \"$version\"\n\n" +
                "/** True in a build the Build workflow made for a release. */\n" +
                "const val RELEASE_BUILD = $release\n",
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
    implementation(libs.sentry)
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
