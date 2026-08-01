// Top-level build file. Toolchain proven by the sibling tt9 build on this machine:
// AGP 9.2.1 · Gradle 9.4.1 · JDK 21. Kotlin + Compose added for SideHome.
plugins {
    // AGP 9 ships built-in Kotlin support (bundled Kotlin 2.2.10), so the standalone
    // kotlin-android plugin is NOT applied — only the Compose compiler plugin is added.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
