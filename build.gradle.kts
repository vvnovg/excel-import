plugins {
    `java-library`
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "org.novgorodtsev.excelimport"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.vanniktech.maven.publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        // sources- и javadoc-jar создаёт плагин com.vanniktech.maven.publish
        // (задачи plainSourcesJar / plainJavadocJar) — ручные withSourcesJar()/withJavadocJar()
        // здесь не нужны и конфликтуют с ними дублирующим выходным файлом.
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Репродусибл-архивы
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    // Публикация в Maven Central (Sonatype Central Portal) через com.vanniktech.maven.publish.
    // Плагин сам применяет maven-publish и подпись; учётные данные (mavenCentralUsername/
    // mavenCentralPassword, signing*) читаются из project properties / ~/.gradle/gradle.properties,
    // в репозитории их быть не должно.
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        coordinates(project.group.toString(), project.name, project.version.toString())
        pom {
            name.set(project.name)
            description.set("Streaming Excel to PostgreSQL importer")
            url.set("https://github.com/vvnovg/excel-import")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("vvnovg")
                    name.set("Viacheslav Novgorodtsev")
                    url.set("https://github.com/vvnovg")
                }
            }
            scm {
                url.set("https://github.com/vvnovg/excel-import")
                connection.set("scm:git:https://github.com/vvnovg/excel-import.git")
                developerConnection.set("scm:git:git@github.com:vvnovg/excel-import.git")
            }
        }
        signAllPublications()
        publishToMavenCentral(automaticRelease = false)
    }
}

// `libs` не виден внутри subprojects{} напрямую — пробрасываем как val
val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByType()