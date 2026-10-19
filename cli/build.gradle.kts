import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileOutputStream

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val cliVersionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.3.2"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

application {
    mainClass.set("dm1sh.hedgehog.cli.HedgehogCliKt")
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/kotlin")
            srcDir("../app/src/main/java")
            include(
                "dm1sh/hedgehog/cli/**",
                "dm1sh/hedgehog/model/DefaultVoices.kt",
                "dm1sh/hedgehog/book/AudioMetadataTagger.kt",
                "dm1sh/hedgehog/book/BookConversionIndex.kt",
                "dm1sh/hedgehog/book/ProcessingFile.kt",
                "dm1sh/hedgehog/book/TextsConverter.kt",
                "dm1sh/hedgehog/tts/SecMsGecGenerator.kt",
                "dm1sh/hedgehog/tts/SocketEdgeTtsWorker.kt",
                "dm1sh/hedgehog/tts/OfflineInstaller.kt",
                "dm1sh/hedgehog/tts/OfflineTtsEngine.kt",
                "dm1sh/hedgehog/tts/OpusConverter.kt"
            )
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("net.sf.kxml:kxml2:2.3.0")
    implementation("com.microsoft.cognitiveservices.speech:client-sdk-embedded:1.40.0@jar")
}

val fatJar = tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a standalone fat JAR containing Hedgehog CLI and all dependencies."
    archiveFileName.set("hedgehog-cli.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "dm1sh.hedgehog.cli.HedgehogCliKt",
            "Implementation-Title" to "hedgehog-cli",
            "Implementation-Version" to cliVersionName,
            "Enable-Native-Access" to "ALL-UNNAMED"
        )
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/MANIFEST.MF"
        )
    }
}

tasks.register("executableCli") {
    group = "build"
    description = "Creates a self-executing Linux binary 'hedgehog-cli' wrapping hedgehog-cli.jar."
    dependsOn(fatJar)
    doLast {
        val jarFile = fatJar.get().archiveFile.get().asFile
        val outBin = File(jarFile.parentFile, "hedgehog-cli")
        val stub = (
            "#!/usr/bin/env sh\n" +
                "if java --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then\n" +
                "  exec java --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar \"\$0\" \"\$@\"\n" +
                "fi\n" +
                "exec java -Dfile.encoding=UTF-8 -jar \"\$0\" \"\$@\"\n"
            ).toByteArray(Charsets.UTF_8)
        FileOutputStream(outBin).use { out ->
            out.write(stub)
            jarFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        outBin.setExecutable(true, false)
        println("Built standalone CLI binary: ${outBin.absolutePath} (${outBin.length()} bytes)")
    }
}
