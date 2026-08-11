plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Gradle's Windows test-worker pathing JAR cannot load project classes when
// the checkout path contains non-ASCII characters. Mirror compiled Android
// unit-test classes to the ASCII system temp directory before forking JUnit.
if (rootDir.absolutePath.any { it.code > 127 }) {
    subprojects {
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            val match = Regex("""test(.+)UnitTest""").matchEntire(name)
                ?: return@configureEach
            val mainVariant = match.groupValues[1].replaceFirstChar { it.lowercaseChar() }
            val testVariant = "${mainVariant}UnitTest"
            val portableClasses = file(
                "${System.getProperty("java.io.tmpdir")}/yulin-rider-tests-" +
                    Integer.toHexString(rootDir.absolutePath.hashCode()) +
                    path.replace(':', '-') +
                    "-$testVariant"
            )
            doFirst {
                val projectClasspath = classpath.files.filter {
                    it.absolutePath.startsWith(rootDir.absolutePath)
                }
                project.sync {
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                    from(layout.buildDirectory.dir("tmp/kotlin-classes/$mainVariant"))
                    from(layout.buildDirectory.dir("tmp/kotlin-classes/$testVariant"))
                    from(
                        layout.buildDirectory.dir(
                            "intermediates/javac/$mainVariant/" +
                                "compile${mainVariant.replaceFirstChar { it.uppercaseChar() }}" +
                                "JavaWithJavac/classes"
                        )
                    )
                    from(
                        layout.buildDirectory.dir(
                            "intermediates/javac/$testVariant/" +
                                "compile${testVariant.replaceFirstChar { it.uppercaseChar() }}" +
                                "JavaWithJavac/classes"
                        )
                    )
                    projectClasspath.forEach { entry ->
                        if (entry.isDirectory) {
                            from(entry)
                        } else if (entry.extension.equals("jar", ignoreCase = true)) {
                            from(zipTree(entry)) {
                                include("**/*.class", "META-INF/services/**")
                            }
                        }
                    }
                    into(portableClasses)
                }
                testClassesDirs = files(portableClasses)
                classpath = files(portableClasses).plus(classpath)
            }
        }
    }
}

// With parallel execution, `gradlew clean test… assemble…` may otherwise run
// one module's build while another module is still deleting shared outputs.
// The release/CI contract invokes these tasks together, so make the clean
// phase a real cross-project barrier.
gradle.projectsEvaluated {
    val cleanRequested = gradle.startParameter.taskNames.any {
        it.substringAfterLast(':').equals("clean", ignoreCase = true)
    }
    if (cleanRequested) {
        val cleanTasks = allprojects.mapNotNull { it.tasks.findByName("clean") }
        allprojects.forEach { project ->
            project.tasks.configureEach {
                if (name != "clean") {
                    mustRunAfter(cleanTasks)
                }
            }
        }
    }
}
