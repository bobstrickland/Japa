// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Kotlin itself comes from AGP 9's built-in Kotlin support — applying
    // org.jetbrains.kotlin.android on top of it fails with a duplicate `kotlin` extension.
    id("com.google.devtools.ksp") version "2.1.20-2.0.1" apply false
}