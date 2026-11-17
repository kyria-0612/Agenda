pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional official, checksum-verified compiler cache on TLS-fragile hosts.
        // Empty on normal machines; only the listed build modules may resolve from it.
        val compilerCache=file("dependency-downloads/maven")
        if(compilerCache.isDirectory) maven {
            name="verifiedKspCompiler";url=compilerCache.toURI()
            content {
                includeModule("com.google.devtools.ksp","symbol-processing-aa-embeddable")
                includeModule("com.google.devtools.ksp","symbol-processing")
                includeModule("org.jetbrains.kotlinx","kotlinx-serialization-core-jvm")
                includeModule("org.jetbrains.kotlinx","kotlinx-serialization-json-jvm")
            }
            metadataSources {mavenPom()}
        }
        google();mavenCentral()
    }
}
rootProject.name = "Agenda"
include(":app", ":domain", ":data")
