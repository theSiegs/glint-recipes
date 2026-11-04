// The tile exchange catalog's checker: a standalone copy of Glint's :glint-recipes module
// (src/main, see VERSION) plus the catalog's own rules (src/catalog). No Glint checkout needed.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "glint-recipes-checker"
