rootProject.name = "excel-import"

include("excel-import-core")
include("excel-import-spring-boot-starter")

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
