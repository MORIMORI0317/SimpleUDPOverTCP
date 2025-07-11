plugins {
    kotlin("jvm") version "2.1.21"
    application
    id("com.gradleup.shadow") version "9.0.0-rc1"
}

group = "net.morimori0317.simpleudpovertcp"
version = "1.0.1"

application {
    mainClass = "net.morimori0317.simpleudpovertcp.MainKt"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to application.mainClass)
        attributes("Implementation-Version" to project.version)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("commons-cli:commons-cli:1.9.0")
    implementation("it.unimi.dsi:fastutil:8.5.16")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}
