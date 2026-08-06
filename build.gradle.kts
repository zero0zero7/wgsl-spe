/*
 * Copyright 2025 The wgsl-fuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.jvm.application.tasks.CreateStartScripts

val antlrVersion: String = "4.10"
val jacksonVersion: String = "2.19.0"
val ktorVersion: String = "3.1.3"
val logbackVersion: String = "1.5.18"

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    java
    antlr
}

kotlin {
    jvmToolchain(21)
}

group = "com.wgslfuzz"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:$antlrVersion")
    testImplementation(kotlin("test"))

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
}

tasks.generateGrammarSource {
    outputDirectory =
        layout.buildDirectory
            .dir("generated/sources/main/kotlin/antlr")
            .get()
            .asFile
    arguments = listOf("-visitor", "-package", "com.wgslfuzz")
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    jvmArgs("-Djava.library.path=src/main/cpp/build")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        java {
            srcDir(tasks.generateGrammarSource)
        }
    }
}

tasks.named("compileTestKotlin") {
    dependsOn("generateTestGrammarSource")
}

tasks.register<JavaExec>("runServer") {
    mainClass.set("com.wgslfuzz.server.ServerKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runGenerator") {
    mainClass.set("com.wgslfuzz.tools.GenerateEquivalentShaderJobsKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runJobsViaServer") {
    mainClass.set("com.wgslfuzz.tools.RunJobsViaServerKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("reduceJobViaServer") {
    mainClass.set("com.wgslfuzz.tools.ReduceJobViaServerKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("standAloneShaderHtml") {
    mainClass.set("com.wgslfuzz.tools.StandAloneShaderHtmlKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("compareImages") {
    mainClass.set("com.wgslfuzz.tools.CompareImagesToolKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("parseAndPrettyPrint") {
    mainClass.set("com.wgslfuzz.tools.ParseAndPrettyPrintKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
}

tasks.register<JavaExec>("printShaderWithCommentary") {
    mainClass.set("com.wgslfuzz.tools.PrintShaderWithCommentaryKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("showNumberOfNodes") {
    mainClass.set("com.wgslfuzz.tools.ShowNumberOfAstNodesKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("dumpAst") {
    mainClass.set("com.wgslfuzz.tools.DumpAstKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
}

tasks.register<JavaExec>("printSkeletalPrograms") {
    mainClass.set("com.wgslspe.tools.PrintSkeletalProgramsKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("checkCompilable") {
    mainClass.set("com.wgslspe.tools.CheckCompilableKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
}

tasks.register<JavaExec>("runShader") {
    mainClass.set("com.wgslspe.tools.RunShaderKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Djava.library.path=src/main/cpp/build")
}

tasks.register<JavaExec>("applyDivergentInjections") {
    mainClass.set("com.wgslspe.tools.ApplyDivergentInjectionsKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("executePipeline") {
    mainClass.set("com.wgslspe.tools.PipelineKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Djava.library.path=src/main/cpp/build")
    systemProperty("kotlinx.serialization.json.trace", "true")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}


// To avoid OutOfMemoryError: Metaspace, Build applyDivergentInjections and printSkeletalPrograms loop tools once into standalone launcher scripts instead
// (see fuzz/lib/common.sh: APPLY_DIVERGENT_INJECTIONS_BIN / PRINT_SKELETAL_PROGRAMS_BIN), so per-seed invocations are a bare
// `java -cp <jars> <mainClass> "$@"` with no Gradle involved.
val toolsInstallDir = layout.buildDirectory.dir("install/tools")

// Sync (not Copy): prunes stale jars left behind by dependency-version bumps above, since CreateStartScripts bakes an explicit, versioned jar filename list into the generated script.
val gatherToolLibs by tasks.registering(Sync::class) {
    from(configurations.runtimeClasspath)
    from(tasks.named("jar"))
    into(toolsInstallDir.map { it.dir("lib") })
}

fun registerToolLauncher(taskName: String, mainClassKt: String) =
    tasks.register<CreateStartScripts>("${taskName}Launcher") {
        dependsOn(gatherToolLibs)
        applicationName = taskName
        mainClass.set(mainClassKt)
        outputDir = toolsInstallDir.get().dir("bin").asFile
        classpath = files(configurations.runtimeClasspath, tasks.named("jar"))
    }

registerToolLauncher("applyDivergentInjections", "com.wgslspe.tools.ApplyDivergentInjectionsKt")
registerToolLauncher("printSkeletalPrograms", "com.wgslspe.tools.PrintSkeletalProgramsKt")

tasks.register("installTools") {
    group = "fuzzing"
    description = "Builds standalone launcher scripts for hot-loop fuzzing tools under build/install/tools. " +
        "Rerun after editing their Kotlin sources, changing a dependency version, OR after `./gradlew clean` " +
        "(which wipes build/ entirely, launchers included)."
    dependsOn("applyDivergentInjectionsLauncher", "printSkeletalProgramsLauncher")
}
