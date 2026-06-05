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
    // Hytale's codec validators (e.g. RangeValidator) initialize HytaleLogger in their static
    // block, which requires the custom JUL log manager to be installed first: otherwise the test
    // JVM throws "Log manager wasn't set!". Set it for the whole test run.
    systemProperty("java.util.logging.manager", "com.hypixel.hytale.logger.backend.HytaleLogManager")
}

// Bundle the chemistry reference data into the jar so the registry can load it via
// getResourceAsStream at runtime (and on the test classpath). data/ stays at repo root
// as the source of truth; its contents land at the resource root (e.g. /elements.json).
sourceSets["main"].resources {
    srcDir("data")
    exclude("**/README.md")
}

// Generate the per-solid-substance asset pack (tinted textures + item JSON + lang) from the
// substance color data. Writes into src/main/resources so both the devServer (--mods=src/main)
// and the packaged jar pick the assets up. Run before devServer/build:
//   ./gradlew generateSolidSubstanceAssets
tasks.register<JavaExec>("generateSolidSubstanceAssets") {
    group = "chemistry"
    description = "Bake substance colors into solid-jar textures + emit item defs into the asset pack."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.chonbosmods.chemistry.impl.assetgen.SubstanceAssetGenerator")
    args(
        project.file("assets-src/master_white.png").absolutePath,
        project.file("src/main/resources").absolutePath,
        project.file("assets-src/icon_master.png").absolutePath,
        project.file("assets-src/icon_liquid_mask.png").absolutePath,
    )
}