import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("io.ktor.plugin") version "3.5.2"
}

group = "com.equipo4.nearhouse"
version = "1.0.0"

// Clase que contiene la función main() (archivo Application.kt)
application {
    mainClass.set("com.equipo4.nearhouse.api.ApplicationKt")
}

// Nombre fijo del JAR ejecutable que genera ./gradlew buildFatJar
ktor {
    fatJar {
        archiveFileName.set("nearhouse-api-all.jar")
    }
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.2"

dependencies {
    // Servidor Ktor
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    // JSON de entrada y salida
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Manejo de errores y registro de peticiones
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Autenticación con tokens JWT
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")

    // Base de datos: driver de PostgreSQL y pool de conexiones
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:6.3.0")

    // Hash de contraseñas con bcrypt
    implementation("org.mindrot:jbcrypt:0.4")

    // Logs en consola
    implementation("ch.qos.logback:logback-classic:1.5.18")

    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.auth0:jwks-rsa:0.22.1")
}

// Compila para Java 21 (la versión que usan Docker y Railway)
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
