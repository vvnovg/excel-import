dependencies {
    api(project(":excel-import-core"))
    implementation(libs.spring.boot.autoconfigure)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
}
