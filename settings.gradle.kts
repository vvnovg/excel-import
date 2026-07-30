rootProject.name = "excel-import"

include("excel-import-core")
include("excel-import-spring-boot-starter")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
