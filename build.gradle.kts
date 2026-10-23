import java.util.Calendar

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

/*
 * The version, as ChurchPresenter's: the two-digit year, then the commit count split across two
 * segments, because a Windows installer allows only 0-255 in each (26.0.42). The release workflow
 * passes the one it tags as -PsignageVersion; a build without it works it out the same way.
 */
val yearDigits = 100
val segment = 256
val signageVersion: String = providers.gradleProperty("signageVersion").orNull?.removePrefix("v") ?: run {
    val count = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 0
    "${Calendar.getInstance().get(Calendar.YEAR) % yearDigits}.${count / segment}.${count % segment}"
}

subprojects {
    group = "org.churchpresenter.signage"
    version = signageVersion
}
