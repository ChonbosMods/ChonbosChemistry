<h1 align="center">Chonbo's Chemistry</h1>

<p align="center">
  <em>The foundational chemistry layer for Hytale: substances, radiation, and containment, done once.</em>
</p>

---

## What is Chonbo's Chemistry?

Chonbo's Chemistry is a chemistry mod for Hytale. The foundation is a **shared substance data model**, a **radiation engine**, a **payload-agnostic containment/affliction system**, and a **clean API** that everything else plugs into.

## Architecture

The codebase splits along a **code/audience boundary**, enforced as two top-level packages:

| Package | Contents | Audience |
|---|---|---|
| `com.chonbosmods.chemistry.api` | interfaces, substance/isotope/compound schema, tags, registry/payload/containment contracts, the Hytale API shim. Zero gameplay, zero content, zero concrete values. | The contract third-party modders build against |
| `com.chonbosmods.chemistry.impl` | substances and generated forms, the radiation engine, gear and instruments, containment implementation, the periodic table, basic machines and baseline recipes. | The working feature players install |

**Governing rule: `api` defines, `impl` decides.** The API says a radiation source *has* an intensity and a penetrating flag; the impl decides the falloff curve, the band thresholds, the tick budget. No concrete value or gameplay logic may leak up into `api`. (Currently one Gradle module; the `api` package is positioned to be promoted to a standalone published artifact once its surface stabilizes.)

## Development

Requires **Java 25** (Temurin `25.0.2-tem` via SDKMAN; see `.sdkmanrc`) and a local Hytale install.

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk env                 # picks up .sdkmanrc
./gradlew compileJava   # compile check
./gradlew devServer     # compile, deploy, and launch the dev server
```

Built on the [ScaffoldIt](https://scaffoldit.dev) Gradle plugin, targeting Hytale Update 5 (`0.5.x`).

## License

Proprietary. See [LICENSE](LICENSE). Copyright (c) 2026 ChonbosMods. All rights reserved.
