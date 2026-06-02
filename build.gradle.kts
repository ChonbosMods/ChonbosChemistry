/**
 * NOTE: This is entirely optional and basics can be done in `settings.gradle.kts`
 */

repositories {
    // Any external repositories besides: MavenLocal, MavenCentral, HytaleMaven, and CurseMaven
}

dependencies {
    // Any external dependency you also want to include

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Bundle the chemistry reference data into the jar so the registry can load it via
// getResourceAsStream at runtime (and on the test classpath). data/ stays at repo root
// as the source of truth; its contents land at the resource root (e.g. /elements.json).
sourceSets["main"].resources {
    srcDir("data")
    exclude("**/README.md")
}