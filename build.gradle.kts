import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.bundling.Zip

plugins {
    `java-library`
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "dev.saltmotion"
version = "0.2.2"
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0") { exclude(group = "org.jetbrains.kotlin") }
    implementation("com.squareup.okio:okio-jvm:3.9.1") { exclude(group = "org.jetbrains.kotlin") }
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
    compileOnly("org.jetbrains.compose.foundation:foundation:1.12.1")
    compileOnly("net.java.dev.jna:jna:5.18.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    testCompileOnly("org.jetbrains.compose.foundation:foundation:1.12.1")
    testImplementation("com.github.Moriafly:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
}
configurations.testCompileOnly { extendsFrom(configurations.compileOnly.get()) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.optIn.add("com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi")
}

tasks.test { systemProperty("file.encoding", "UTF-8") }
tasks.register<JavaExec>("runtimeRegression") {
    dependsOn(tasks.testClasses)
    setExecutable(providers.gradleProperty("hostRuntimeJava").getOrElse("java"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.junit.runner.JUnitCore")
    args("dev.saltmotion.MotionBehaviorTest", "dev.saltmotion.AppleHttpRegressionTest", "dev.saltmotion.MotionVideoCacheTest", "dev.saltmotion.CoverEditorTest", "dev.saltmotion.ArtworkPathTest")
    systemProperty("saltmotion.trimmedRuntime", "true")
    systemProperty("file.encoding", "UTF-8")
}
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named<Zip>("plugin") {
    from("LICENSE", "NOTICE")
    from(zipTree(tasks.jar.flatMap { it.archiveFile })) { include("META-INF/MANIFEST.MF") }
}
spmod {
    PluginClass = "dev.saltmotion.SaltMotionPlugin"
    PluginId = "dev.saltmotion"
    PluginVersion = project.version.toString()
    PluginName = "Salt Motion"
    PluginDescription = "动态专辑封面与多来源封面自定义"
    PluginProvider = "asukua"
    PluginHasConfig = true
    PluginOpenSourceUrl = "https://github.com/asukua/salt-motion"
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM, PluginPermission.LIBRARY_READ, PluginPermission.LIBRARY_WRITE)
}
