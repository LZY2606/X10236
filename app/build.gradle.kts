plugins {
    application
}

group = "neuro.spike"
version = "1.0.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("neuro.spike.Main")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.4")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
