plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.allanlewis"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    runtimeOnly("com.h2database:h2")

    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    // Starting provider is Anthropic (pay-as-you-go), but business logic depends
    // only on Spring AI's ChatClient abstraction so this starter is swappable.
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")

    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
    imageName.set("allanelewis/homelab-watchman:${project.version}")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
}
