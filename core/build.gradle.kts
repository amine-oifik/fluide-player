import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: no Android, no JVM-only I/O in the public API (CharReader instead of java.io.Reader),
// so it can become the commonMain of a Kotlin Multiplatform module for Apple, Tizen and webOS later.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
