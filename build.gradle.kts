plugins {
    alias(libs.plugins.android.application) apply false
    // Declaring the Kotlin plugin here also pins the Kotlin version that AGP's built-in Kotlin uses for :app.
    alias(libs.plugins.kotlin.jvm) apply false
}
