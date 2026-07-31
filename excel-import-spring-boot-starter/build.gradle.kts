dependencies {
    api(project(":excel-import-core"))
    implementation(libs.spring.boot.autoconfigure)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.h2)
}

tasks.named<JavaCompile>("compileJava") {
    // configuration-processor не «claim'ит» аннотации Spring, и javac категории processing
    // выдаёт «No processor claimed any of these annotations», что ломает -Werror.
    // Это ожидаемое поведение процессора (запускается только для main-исходников), поэтому
    // глушится только эта категория lint'а и только на compileJava.
    options.compilerArgs.add("-Xlint:-processing")
}
