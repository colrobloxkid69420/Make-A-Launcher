pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "make-a-launcher"

// core: the platform-neutral engine (scene model, reactive graph, layout, animation).
//       Pure Kotlin/JVM with no Android imports, so it can later compile for desktop or web previews.
// app:  the Android launcher that hosts the engine and renders it with RenderNode.
include(":core", ":app")
