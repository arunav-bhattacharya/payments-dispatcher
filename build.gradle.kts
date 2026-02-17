plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.allopen") version "2.1.0"
    id("io.quarkus")
}

repositories {
    mavenCentral()
}

val quarkusPlatformVersion: String by project
val temporalSdkVersion: String by project
val exposedVersion: String by project

dependencies {
    // Quarkus BOM
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusPlatformVersion"))

    // Quarkus core
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest-jackson")

    // Oracle JDBC + connection pooling + transactions
    implementation("io.quarkus:quarkus-jdbc-oracle")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-narayana-jta")

    // Temporal SDK
    implementation("io.temporal:temporal-sdk:$temporalSdkVersion")

    // Kotlin Exposed DSL for Oracle DB operations
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // Jackson Kotlin module for JSON CLOB serialization
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Micrometer + Prometheus for metrics
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")

    // Health checks
    implementation("io.quarkus:quarkus-smallrye-health")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Test dependencies
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.mockk:mockk:1.13.16")
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        javaParameters.set(true)
    }
}
