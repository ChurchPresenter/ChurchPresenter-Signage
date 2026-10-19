plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    group = "org.churchpresenter.signage"
    version = "0.1.0"
}
