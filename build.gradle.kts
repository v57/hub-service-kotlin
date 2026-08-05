import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm") version "2.2.21"
}

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
  }
}

dependencies {
  // channel-kotlin lives in the channels monorepo as a sibling repo and is
  // wired in as a composite build (see settings.gradle.kts).
  implementation(":channel-kotlin:unspecified")
  implementation(kotlin("stdlib"))
  implementation(kotlin("reflect"))

  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit5"))
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_21)
    freeCompilerArgs.add("-Xjsr305=strict")
  }
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("failed", "skipped")
  }
}

tasks.register<JavaExec>("run") {
  group = "application"
  description = "Run the echo example service"
  mainClass.set("v57.hubservice.example.EchoMainKt")
  classpath = sourceSets["main"].runtimeClasspath
}

tasks.register("jvmTest") {
  group = "verification"
  description = "Compatibility alias for the JVM test suite"
  dependsOn(tasks.test)
}
