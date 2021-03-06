import com.jfrog.bintray.gradle.BintrayExtension
import net.ltgt.gradle.errorprone.ErrorProneToolChain
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `maven-publish`
    java
    jacoco
    checkstyle
    pmd
    id("info.solidsoft.pitest") version "1.5.1"
    id("com.jfrog.bintray") version "1.6"
    id("net.ltgt.errorprone-base") version "0.0.13"
    id("com.github.spotbugs") version "4.2.4"
}

repositories {
    jcenter()
}

dependencies {
    implementation("com.esotericsoftware:kryo:4.0.1")
    implementation("com.google.protobuf:protobuf-java:3.5.0")
    implementation("io.reactivex:rxjava:1.3.4")
    implementation("org.jetbrains:annotations:15.0")
    errorprone("com.google.errorprone:error_prone_core:2.1.2")
    testImplementation("junit:junit:4.12")
    testImplementation("nl.jqno.equalsverifier:equalsverifier:2.3.3")
}

fun linkGitHub(resource: String = "") = "https://github.com/${project.name}/${project.name}$resource"

val archivesBaseNameProperty = "archivesBaseName"
project.setProperty(archivesBaseNameProperty, project.name.toLowerCase())
val archivesBaseName = { "${project.property(archivesBaseNameProperty)}" }

group = project.name.toLowerCase()
version = "2.1.0"
description = "A small distributed event library for the JVM"

val testSourceSet = sourceSets["test"]!!
sourceSets.create("pitest") {
    java {
        srcDirs(testSourceSet.java.srcDirs)
        exclude("rxbroadcast/integration/**")
    }

    compileClasspath += files(testSourceSet.compileClasspath)
    runtimeClasspath += compileClasspath
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(arrayOf("-Xlint:all", "-Xdiags:verbose", "-Werror"))
    sourceCompatibility = "${JavaVersion.VERSION_1_8}"
    targetCompatibility = "${JavaVersion.VERSION_1_8}"
}

tasks.withType<Javadoc> {
    options.optionFiles(file("config/javadoc.opts"))
}

tasks.withType<Test> {
    exclude("rxbroadcast/integration/**")
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events = setOf(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        showStandardStreams = true
    }
}

task("errorProne") {
    tasks.withType<JavaCompile>().all {
        toolChain = ErrorProneToolChain(configurations.getByName("errorprone"))
    }
    dependsOn.add(tasks.withType<JavaCompile>())
}

task<Jar>("testJar") {
    archiveClassifier.set("tests")
    group = "verification"
    description = "Assembles a jar archive containing the test classes."
    afterEvaluate {
        val sourceSets = convention.getPlugin(JavaPluginConvention::class).sourceSets
        val files = configurations.testCompileClasspath.get().files.map(fun (file: File): Any = when {
            file.isDirectory -> file
            else -> zipTree(file)
        })

        from(sourceSets.findByName("main")!!.output + sourceSets.findByName("test")!!.output)
        from(files) {
            exclude("META-INF/**")
        }
    }
}

pitest {
    excludedMethods.set(setOf("toString", "newThread", "hashCode"))
    detectInlinedCode.set(true)
    timestampedReports.set(false)
    mutationThreshold.set(80)
    mutators.set(setOf("ALL"))
    testSourceSets.set(setOf(sourceSets["pitest"]))
    verbose.set((System.getenv("CI") ?: "false").toBoolean())
}

task<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    afterEvaluate {
        val sourceSets = convention.getPlugin(JavaPluginConvention::class).sourceSets
        from(sourceSets.findByName("main")!!.allSource)
    }
}

task<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    afterEvaluate {
        from(tasks.findByName("javadoc"))
    }
}

pmd {
    toolVersion = "6.21.0"
    ruleSets = emptyList()
    ruleSetFiles("config/pmd/rules.xml")
}

spotbugs {
    toolVersion.set("4.0.3")
    setEffort("max")

    tasks.spotbugsMain {
        reports.create("html") {
            isEnabled = true
            setStylesheet("fancy-hist.xsl")
        }
    }

    tasks.spotbugsTest {
        reports.create("html") {
            isEnabled = true
            setStylesheet("fancy-hist.xsl")
        }
    }
}

checkstyle {
    toolVersion = "8.2"
}

jacoco {
    toolVersion = "0.7.9"
}

publishing {
    publications {
        create(project.name.toLowerCase(), MavenPublication::class.java) {
            from(components.findByName("java"))
            artifactId = archivesBaseName()
            artifact(tasks.getByName("javadocJar"))
            artifact(tasks.getByName("sourcesJar"))
            pom {
                withXml {
                    asNode().apply {
                        appendNode("name", project.name)
                        appendNode("description", project.description)
                        appendNode("url", "http://${project.name.toLowerCase()}.website")
                        appendNode("packaging", "jar")

                        appendNode("licenses").appendNode("license").apply {
                            appendNode("name", "ISC")
                            appendNode("url", linkGitHub("/raw/master/LICENSE.md"))
                        }

                        appendNode("scm").apply {
                            appendNode("url", linkGitHub())
                        }

                        appendNode("issueManagement").apply {
                            appendNode("system", "GitHub Issues")
                            appendNode("url", linkGitHub("/issues"))
                        }
                    }
                }
            }
        }
    }
}

configure<BintrayExtension> {
    user = System.getenv("BINTRAY_USER")
    key = System.getenv("BINTRAY_API_KEY")

    setPublications(project.name.toLowerCase())
    publish = true

    pkg = PackageConfig().apply {
        repo = "maven"
        name = project.name
        vcsUrl = linkGitHub()
        setLicenses("ISC")
        version = VersionConfig().apply {
            name = project.version.toString()
            desc = project.description
        }
    }
}
