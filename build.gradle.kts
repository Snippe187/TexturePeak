plugins {
    `java-library`
}

group = "de.peakverse"
version = "0.1.3"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Ein Modul ist kein eigenes Plugin-Jar: Das Projekt PeakVerse/Bundle setzt es mit dem Core zum Jar PeakCore-<Version>.jar zusammen.
dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_api_version")}")
    // Die PeakCore-API ist im fertigen Jar schon dabei, deshalb nur zum Kompilieren
    compileOnly("de.peakverse:core-api")

    testImplementation("io.papermc.paper:paper-api:${property("paper_api_version")}")
    testImplementation("de.peakverse:core-api")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
