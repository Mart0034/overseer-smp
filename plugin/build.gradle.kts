plugins { java }

group = "dev.overseersmp"
version = System.getenv("OVERSEER_VERSION") ?: "0.1.0-dev"

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApi = "io.papermc.paper:paper-api:26.2.build.132-stable"
val sqlite = "org.xerial:sqlite-jdbc:3.50.3.0"

dependencies {
    compileOnly(paperApi)          // brings Gson, Adventure; provided by the server at runtime
    compileOnly(sqlite)            // Paper ships sqlite-jdbc
    testImplementation(paperApi)
    testImplementation(sqlite)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.test { useJUnitPlatform(); testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
tasks.jar { archiveFileName.set("Overseer-${project.version}.jar") }
