rootProject.name = "ChonbosChemistry"

plugins {
    // See documentation on https://scaffoldit.dev
    // 0.2.16+ declares its devtools dependency on Hytale:Universe with SemVer
    // (>=0.5.2); 0.2.14 used the old date-string and won't load under U5.
    id("dev.scaffoldit") version "0.2.16"
}

hytale {
    usePatchline("release")
    // Update 5 stable. SDK switched to SemVer; 0.5.3 is the U5 hotfix line (2026-05-29).
    useVersion("0.5.3")

    repositories {
    }

    dependencies {
    }

    manifest {
        Group = "chonbosmods"
        Name = "ChonbosChemistry"
        Main = "com.chonbosmods.chemistry.ChonbosChemistry"
        Version = settings.providers.gradleProperty("version").get()
    }
}
