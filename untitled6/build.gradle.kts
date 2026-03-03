plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "1.9.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("de.fabmax.kool:kool-core:0.19.0")
    implementation("de.fabmax.kool:kool-physics:0.19.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

}

tasks.test {
    useJUnitPlatform()
}
tasks.withType<JavaExec> {
    jvmArgs = listOf("-XstartOnFirstThread")
}
kotlin {
    jvmToolchain(17)
}