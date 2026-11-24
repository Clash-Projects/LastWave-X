import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val isolatedFeatureModules = setOf(
    ":core-data",
    ":innertube",
    ":audiodsp",
    ":player",
    ":lyrics",
    ":integrations",
    ":addons",
)

subprojects {
    val currentPath = path
    configurations.configureEach {
        dependencies.withType(ProjectDependency::class.java).configureEach {
            val targetPath = this.path
            if (targetPath == currentPath) return@configureEach
            when (currentPath) {
                ":core-model" -> error("Module :core-model must not depend on any project module (found $targetPath).")
                in isolatedFeatureModules -> {
                    if (targetPath != ":core-model") {
                        error("Module $currentPath may only depend on :core-model (forbidden dependency: $targetPath).")
                    }
                }
            }
        }
    }
}
