plugins {
    java
    application
}

group = "neuro.spike"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("io.javalin:javalin:6.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "neuro.spike.Main"
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
