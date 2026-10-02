pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "Panelglass"
include(":app")
include(":core:model")
include(":core:ui")
include(":core:data")
include(":core:engine")
include(":core:ocr")
include(":core:render")
include(":core:pipeline")
include(":feature:browser")
include(":feature:library")
include(":feature:settings")
include(":feature:studio")
