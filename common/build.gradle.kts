plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.5"))
    implementation("tools.jackson.module:jackson-module-kotlin:3.0.4")
    implementation("org.springframework:spring-web")
    implementation("org.slf4j:slf4j-api")
    // Outbox 패턴: Entity + Repository 공유
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.springframework.data:spring-data-jpa")
    compileOnly("org.springframework.kafka:spring-kafka")
    implementation(kotlin("stdlib"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}
repositories {
    mavenCentral()
}