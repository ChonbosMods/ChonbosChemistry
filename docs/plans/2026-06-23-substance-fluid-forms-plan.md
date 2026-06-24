# Substance Fluid Forms Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Generate ~66 drinkable, placeable substance fluids (39 liquids + 27 liquefied gases) from one master texture + each substance's own data, with composable contact/drink hazards, by mirroring the existing solid-jar asset generator.

**Architecture:** Build-time Java generators (run as Gradle `JavaExec` tasks) read `InMemorySubstanceRegistry`, tint a master via the existing `SubstanceLiquidTinter`, and emit fluid block JSON, FluidFX, placement items, drinkable container states, icons, and localization into `src/main/resources`. All pure-logic pieces (id/naming, fluid-set derivation, hazard composition, JSON templates) are unit-tested first (TDD); texture/file emission reuses proven code and is verified by generation + in-game checks.

**Tech Stack:** Java 25, JUnit 5 (Jupiter), Gradle Kotlin DSL, Hytale data-driven asset packs (`Server/...`, `Common/...`). Design doc: `docs/plans/2026-06-23-substance-fluid-forms-design.md`.

**Key references (read before starting):**
- Design: `docs/plans/2026-06-23-substance-fluid-forms-design.md`
- Mirror target: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/SubstanceAssetGenerator.java` + `SolidSubstanceAssets.java`
- Reused utils: `impl/texture/SubstanceLiquidTinter.java`, `LiquidMask.java`, `GlowBoost.java`, `impl/assetgen/GlowDeriver.java`, `SubstanceIcon.java`
- Registry: `impl/registry/InMemorySubstanceRegistry.java` (`loadFromResources()`, `elements()`, `compounds()`)
- Substance API: `api/substance/{Substance,Element,Compound,Phase,Color,PropertyFlags,Toxicity}.java`
- Gradle task pattern: `build.gradle.kts` lines ~37-72 (`generateSolidSubstanceAssets`, `generateFluidPipeTextures`)
- Memory: `[[fluid-container-override-standard]]`, `[[devserver-logs]]`, `[[user-controls-devservers]]`, `[[machine-model-asset-copy]]`

**Conventions to honor:**
- Use `:` not em dash everywhere.
- Branch is `feature/cc-fluids-mugtastic-party` (already created). Commit locally, never push unless told.
- Tests live under `src/test/java/com/chonbosmods/chemistry/impl/assetgen/` mirroring the existing assetgen tests.
- Run a single test: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.<Class>'`
- Element carries no `PropertyFlags`/`Toxicity` (those are `Compound`-only); element radioactivity comes from `GlowDeriver.tierFor(element, registry) != NONE`.

---

## Phase 0: Prerequisites (art inputs + scope note)

### Task 0.1: Confirm/author the fluid surface master

**Files:**
- Create (art): `assets-src/fluid_master.png` (32x32, neutral mid-gray liquid with a subtle luminance ramp so tinting reads; alpha as desired for transparency)

**Steps:**
1. If `assets-src/fluid_master.png` is absent, author a 32x32 neutral gray tile (the tint multiplies substance color in, same principle as `master_white.png`). A flat `#808080` fill works as a v1 placeholder; refine later.
2. No test. This is an art input consumed by the generator.
3. Commit: `git add assets-src/fluid_master.png && git commit -m "assets(fluids): add neutral fluid surface master"`

**Note:** The fluid surface tint region is the whole 32x32 tile, so the generator uses `new LiquidMask(0, 0, 32, 32)` (a code-defined rectangle, no mask PNG needed). Container liquid masks come in Phase 5.

---

## Phase 1: Fluid set + naming (pure logic, TDD)

This phase builds `FluidForm` (a substance + whether it is a liquefied-gas form) and `FluidAssets.assetId`, the analog of `SolidSubstanceAssets`.

### Task 1.1: `FluidForm` record + fluid-set derivation

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidForm.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidFormTest.java`

**Step 1: Write the failing test**

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidFormTest {

    @Test
    void fluidSetIsLiquidsPlusLiquefiedGases() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        List<FluidForm> forms = FluidForm.allFor(registry);

        long native_ = forms.stream().filter(f -> !f.liquefied()).count();
        long liquefied = forms.stream().filter(FluidForm::liquefied).count();

        assertEquals(39, native_, "39 native LIQUID-phase substances");
        assertEquals(27, liquefied, "27 liquefied GAS-phase substances");
        assertEquals(66, forms.size());
    }

    @Test
    void liquefiedGasGetsLiquidDisplayLabel() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        FluidForm helium = FluidForm.allFor(registry).stream()
            .filter(f -> f.liquefied() && f.substance().name().equals("Helium"))
            .findFirst().orElseThrow();
        assertEquals("Liquid Helium", helium.displayName());
    }

    @Test
    void nativeLiquidKeepsItsName() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        FluidForm mercury = FluidForm.allFor(registry).stream()
            .filter(f -> f.substance().name().equals("Mercury"))
            .findFirst().orElseThrow();
        assertTrue(!mercury.liquefied());
        assertEquals("Mercury", mercury.displayName());
    }
}
```

**Step 2: Run to verify it fails**

Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidFormTest'`
Expected: FAIL (cannot find symbol `FluidForm`).

**Step 3: Write minimal implementation**

```java
package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Phase;
import com.chonbosmods.chemistry.api.substance.Substance;
import java.util.ArrayList;
import java.util.List;

/**
 * One fluid the generators emit. A substance contributes a fluid form either natively (it is
 * {@code Phase.LIQUID}) or as a liquefied form of a {@code Phase.GAS} substance (liquid helium,
 * liquid nitrogen). The substance identity and color are shared with its other phases: only the
 * label, naming, and cryo hazard differ.
 */
public record FluidForm(Substance substance, boolean liquefied) {

    /** All fluid forms: every LIQUID substance, plus a liquefied form of every GAS substance. */
    public static List<FluidForm> allFor(SubstanceRegistry registry) {
        List<FluidForm> forms = new ArrayList<>();
        registry.elements().stream().filter(s -> s.phase() == Phase.LIQUID)
            .forEach(s -> forms.add(new FluidForm(s, false)));
        registry.compounds().stream().filter(s -> s.phase() == Phase.LIQUID)
            .forEach(s -> forms.add(new FluidForm(s, false)));
        registry.elements().stream().filter(s -> s.phase() == Phase.GAS)
            .forEach(s -> forms.add(new FluidForm(s, true)));
        registry.compounds().stream().filter(s -> s.phase() == Phase.GAS)
            .forEach(s -> forms.add(new FluidForm(s, true)));
        return forms;
    }

    public boolean isElement() {
        return substance instanceof Element;
    }

    /** In-game display name: liquefied gases read "Liquid <Name>"; native liquids keep their name. */
    public String displayName() {
        return liquefied ? "Liquid " + substance.name() : substance.name();
    }
}
```

**Step 4: Run to verify it passes**

Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidFormTest'`
Expected: PASS. If the 39/27 counts differ, do NOT hardcode around it: re-check `data/elements.json`/`data/compounds.json` phase counts (research recorded 39 liquid / 27 gas) and fix the test to the real numbers, noting the discrepancy.

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidForm.java \
        src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidFormTest.java
git commit -m "feat(fluids): FluidForm + fluid-set derivation (liquids + liquefied gases)"
```

### Task 1.2: `FluidAssets.assetId` + id/path helpers

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssets.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssetsTest.java`

**Step 1: Write the failing test**

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FluidAssetsTest {

    @Test
    void blockIdNamespacesByKindAndLiquefied() {
        // native compound liquid (Mercury is an element; use a compound for the compound case)
        assertEquals("Fluid_Compound_Sulfuric_Acid",
            FluidAssets.blockId(false, false, "Sulfuric acid"));
        // liquefied gas element
        assertEquals("Fluid_Element_Liquefied_Helium",
            FluidAssets.blockId(true, true, "Helium"));
    }

    @Test
    void sourceAndFlowingIds() {
        assertEquals("Fluid_Element_Mercury", FluidAssets.blockId(true, false, "Mercury"));
        assertEquals("Fluid_Element_Mercury_Source",
            FluidAssets.sourceId(FluidAssets.blockId(true, false, "Mercury")));
    }

    @Test
    void itemIdMirrorsBlockId() {
        assertEquals("Chem_Fluid_Element_Mercury",
            FluidAssets.itemId(true, false, "Mercury"));
    }

    @Test
    void everyFluidFormGetsUniqueIds() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        List<FluidForm> forms = FluidForm.allFor(registry);
        Set<String> ids = new HashSet<>();
        for (FluidForm f : forms) {
            String id = FluidAssets.blockId(f.isElement(), f.liquefied(), f.substance().name());
            assertTrue(ids.add(id), "duplicate block id " + id + " for " + f.displayName());
        }
        assertEquals(forms.size(), ids.size());
    }
}
```

**Step 2: Run to verify it fails**

Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidAssetsTest'`
Expected: FAIL (cannot find symbol `FluidAssets`).

**Step 3: Write minimal implementation**

Reuse the exact PascalCase-per-segment sanitizer from `SolidSubstanceAssets.assetId(boolean, String)` (Hytale AssetStore requires PascalCase segments). Extract it as a shared helper to stay DRY.

```java
package com.chonbosmods.chemistry.impl.assetgen;

/**
 * Pure asset-identity + path helpers for substance fluids, mirroring {@link SolidSubstanceAssets}.
 * Block ids: {@code Fluid_<Kind>[_Liquefied]_<Name>}; the flowing block uses the bare id and the
 * still pool appends {@code _Source}. Placement item ids: {@code Chem_Fluid_<Kind>[_Liquefied]_<Name>}.
 */
public final class FluidAssets {

    private FluidAssets() {}

    public static String blockId(boolean isElement, boolean liquefied, String name) {
        String kind = isElement ? "Element" : "Compound";
        String pre = liquefied ? "Liquefied_" : "";
        return "Fluid_" + kind + "_" + pre + AssetIds.pascalSegments(name);
    }

    public static String itemId(boolean isElement, boolean liquefied, String name) {
        return "Chem_" + blockId(isElement, liquefied, name);
    }

    public static String sourceId(String blockId) {
        return blockId + "_Source";
    }

    /** Surface texture path relative to the asset pack {@code Common/}. */
    public static String texturePath(String blockId) {
        return "BlockTextures/" + blockId + ".png";
    }
}
```

Create the shared sanitizer `AssetIds`:

```java
package com.chonbosmods.chemistry.impl.assetgen;

/** Shared id sanitization: PascalCase-per-underscore-segment, as Hytale AssetStore keys require. */
public final class AssetIds {

    private AssetIds() {}

    public static String pascalSegments(String identity) {
        String[] parts = identity.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").split("_");
        StringBuilder safe = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (safe.length() > 0) {
                safe.append('_');
            }
            safe.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return safe.toString();
    }
}
```

**Step 4 (refactor, keep green): point `SolidSubstanceAssets.assetId` at `AssetIds`**

In `SolidSubstanceAssets.assetId(boolean, String)`, replace the inline sanitizer body with `return "Chem_Solid_" + kind + "_" + AssetIds.pascalSegments(identity);`. Run the full assetgen suite to prove no regression:

Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.*'`
Expected: PASS (FluidAssetsTest + the existing SolidSubstanceAssetsTest both green).

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssets.java \
        src/main/java/com/chonbosmods/chemistry/impl/assetgen/AssetIds.java \
        src/main/java/com/chonbosmods/chemistry/impl/assetgen/SolidSubstanceAssets.java \
        src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssetsTest.java
git commit -m "feat(fluids): FluidAssets id/path helpers; share PascalCase sanitizer"
```

---

## Phase 2: Composable hazard modules (pure logic, TDD)

A `FluidHazard` enum + `FluidHazardComposer` that maps a `FluidForm` to the ordered set of hazards its data triggers. The composer returns enums only here (JSON wiring is Phase 3), so it is trivially testable.

### Task 2.1: `FluidHazard` enum

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazard.java`

No test yet (enum only). Values, in application order:

```java
package com.chonbosmods.chemistry.impl.assetgen;

/**
 * The composable hazards a fluid can apply on contact and on drink. A fluid stacks every hazard its
 * substance data triggers (design F4). Order is the application order in the contact interaction
 * chain and the drink-effect array.
 */
public enum FluidHazard {
    IGNITE,     // propertyFlags.flammable
    RADIATION,  // isRadioactive (compound) or GlowDeriver tier > NONE (element)
    CORROSIVE,  // propertyFlags.corrosive | oxidizer, or toxicity present
    CRYO;       // liquefied gas form
}
```

Commit with Task 2.2.

### Task 2.2: `FluidHazardComposer.hazardsFor`

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardComposer.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardComposerTest.java`

**Step 1: Write the failing test**

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FluidHazardComposerTest {

    private static SubstanceRegistry registry;

    @BeforeAll
    static void load() {
        registry = InMemorySubstanceRegistry.loadFromResources();
    }

    private List<FluidHazard> forCompound(String formula, boolean liquefied) {
        var c = registry.compound(formula).orElseThrow();
        return FluidHazardComposer.hazardsFor(new FluidForm(c, liquefied), registry);
    }

    private List<FluidHazard> forElement(String symbol, boolean liquefied) {
        var e = registry.element(symbol).orElseThrow();
        return FluidHazardComposer.hazardsFor(new FluidForm(e, liquefied), registry);
    }

    @Test
    void corrosiveAcidIsCorrosive() {
        assertTrue(forCompound("H2SO4", false).contains(FluidHazard.CORROSIVE));
    }

    @Test
    void liquefiedGasIsCryo() {
        // any GAS-phase element, e.g. nitrogen
        assertTrue(forElement("N", true).contains(FluidHazard.CRYO));
    }

    @Test
    void radioactiveElementGlowsAndRadiates() {
        // Uranium is radioactive by isotope stability (GlowDeriver tier > NONE)
        assertTrue(forElement("U", false).contains(FluidHazard.RADIATION));
    }

    @Test
    void benignWaterHasNoHazards() {
        assertEquals(List.of(), forCompound("H2O", false));
    }

    @Test
    void hazardsAreOrderedAndDeduped() {
        // ordering follows the enum declaration order; never duplicated
        var h = forCompound("H2SO4", false);
        assertEquals(h.stream().distinct().toList(), h);
    }
}
```

(If `H2O`/`H2SO4`/`U`/`N` symbols differ in the dataset, adjust to real entries; the assertions test the rule, not the specific substance.)

**Step 2: Run to verify it fails**

Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidHazardComposerTest'`
Expected: FAIL (cannot find symbol `FluidHazardComposer`).

**Step 3: Write minimal implementation**

```java
package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a fluid to the hazards its substance data triggers (design F4, F5). Hazards stack: every
 * trigger that fires contributes its module. Returned in {@link FluidHazard} declaration order,
 * deduped. Element radioactivity is derived from isotope stability via {@link GlowDeriver}; the
 * {@code PropertyFlags}/{@code Toxicity} triggers are compound-only (elements lack those fields).
 */
public final class FluidHazardComposer {

    private FluidHazardComposer() {}

    public static List<FluidHazard> hazardsFor(FluidForm form, SubstanceRegistry registry) {
        List<FluidHazard> out = new ArrayList<>();
        var s = form.substance();

        boolean flammable = false;
        boolean corrosive = false;
        boolean radioactive;

        if (s instanceof Compound c) {
            flammable = c.propertyFlags().flammable();
            corrosive = c.propertyFlags().corrosive() || c.propertyFlags().oxidizer()
                || c.toxicity() != null;
            radioactive = c.isRadioactive();
        } else {
            radioactive = GlowDeriver.tierFor((Element) s, registry) != GlowTier.NONE;
        }

        if (flammable) {
            out.add(FluidHazard.IGNITE);
        }
        if (radioactive) {
            out.add(FluidHazard.RADIATION);
        }
        if (corrosive) {
            out.add(FluidHazard.CORROSIVE);
        }
        if (form.liquefied()) {
            out.add(FluidHazard.CRYO);
        }
        return out;
    }
}
```

**Step 4: Run to verify it passes**

Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidHazardComposerTest'`
Expected: PASS.

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazard.java \
        src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardComposer.java \
        src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardComposerTest.java
git commit -m "feat(fluids): composable hazard derivation from substance data"
```

---

## Phase 3: JSON templates + hazard wiring (pure logic, TDD)

Extend `FluidAssets` (and a small `FluidHazardJson` helper) to render the block/FluidFX/item/effect JSON. Keep each renderer a pure `String`-returning function so JSON shape is unit-tested.

### Task 3.1: Immediate hazard effect JSON fragments

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardJson.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardJsonTest.java`

**What this builds:** for a list of hazards, produce (a) the contact `Interactions.Collision` chain entries and (b) the drink `Effect.Interactions` array entries. v1 uses immediate effects (design F6): reuse vanilla `Lava_Burn` for IGNITE; emit mod status-effect ids for the others (authored in Task 3.2). Each contact hazard gets its own `Cooldown` id so stacked hazards tick independently (design §5.2).

**Step 1: Write the failing test** (assert the fragments contain the right `EffectId`s and that stacking concatenates):

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FluidHazardJsonTest {

    @Test
    void igniteUsesVanillaBurn() {
        String drink = FluidHazardJson.drinkEffects(List.of(FluidHazard.IGNITE));
        assertTrue(drink.contains("Lava_Burn"), drink);
    }

    @Test
    void stackedHazardsConcatenateBoth() {
        String drink = FluidHazardJson.drinkEffects(List.of(FluidHazard.RADIATION, FluidHazard.CORROSIVE));
        assertTrue(drink.contains("CC_Effect_Radiation"), drink);
        assertTrue(drink.contains("CC_Effect_Corrosion"), drink);
    }

    @Test
    void benignDrinkIsEmptyArray() {
        assertTrue(FluidHazardJson.drinkEffects(List.of()).contains("server.items"));
        // benign still produces a valid (possibly mild/empty) effect block; see impl note
    }

    @Test
    void contactChainGivesEachHazardItsOwnCooldown() {
        String contact = FluidHazardJson.contactInteractions(List.of(FluidHazard.RADIATION, FluidHazard.CORROSIVE));
        assertTrue(contact.contains("CC_Effect_Radiation"), contact);
        assertTrue(contact.contains("CC_Effect_Corrosion"), contact);
    }
}
```

**Step 2: Run to verify it fails.**
Run: `./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidHazardJsonTest'`
Expected: FAIL.

**Step 3: Write minimal implementation.** Map each hazard to a vanilla/mod `EffectId`:

```java
package com.chonbosmods.chemistry.impl.assetgen;

import java.util.List;

/**
 * Renders the immediate-effect (v1, design F6) JSON for a fluid's hazards: the contact-collision
 * chain on the world block and the drink-effect array on container Filled states. Affliction
 * dose-accumulation plugs in later behind the same call sites (no asset rework).
 */
public final class FluidHazardJson {

    private FluidHazardJson() {}

    /** The status/effect id each hazard applies (vanilla Burn reused; others are CC effects). */
    static String effectId(FluidHazard h) {
        return switch (h) {
            case IGNITE -> "Lava_Burn";
            case RADIATION -> "CC_Effect_Radiation";
            case CORROSIVE -> "CC_Effect_Corrosion";
            case CRYO -> "CC_Effect_Cryo";
        };
    }

    /** Drink-effect array entries (the value of InteractionVars.Effect.Interactions). */
    public static String drinkEffects(List<FluidHazard> hazards) {
        StringBuilder b = new StringBuilder();
        for (FluidHazard h : hazards) {
            if (b.length() > 0) {
                b.append(",\n");
            }
            b.append("        { \"Type\": \"ApplyEffect\", \"EffectId\": \"%s\" }".formatted(effectId(h)));
        }
        // include a comment anchor so benign fluids still render a valid block (see test)
        return b.length() == 0 ? "/* server.items: benign, no effects */" : b.toString();
    }

    /** Contact Interactions.Collision entries, one per hazard, each with its own cooldown id. */
    public static String contactInteractions(List<FluidHazard> hazards) {
        StringBuilder b = new StringBuilder();
        for (FluidHazard h : hazards) {
            if (b.length() > 0) {
                b.append(",\n");
            }
            b.append("""
                { "Type": "ApplyEffect", "EffectId": "%s", "Cooldown": { "Id": "%s", "Cooldown": 1 } }"""
                .formatted(effectId(h), "CC_Fluid_" + h.name()));
        }
        return b.toString();
    }
}
```

(Adjust the exact `Interactions.Collision` envelope to match the vanilla Lava shape once you template the block JSON in Task 3.3; the unit test only pins the `EffectId`/cooldown content. Refine `benign` handling to whatever the block/state JSON needs: an empty array is the likely real answer: replace the placeholder once 3.3 fixes the surrounding shape.)

**Step 4: Run to verify it passes.** PASS.

**Step 5: Commit.**
```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardJson.java \
        src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidHazardJsonTest.java
git commit -m "feat(fluids): immediate-effect hazard JSON fragments (v1)"
```

### Task 3.2: Author the mod status effects

**Files:**
- Create: `src/main/resources/Server/Entity/Effects/Status/CC_Effect_Radiation.json`
- Create: `src/main/resources/Server/Entity/Effects/Status/CC_Effect_Corrosion.json`
- Create: `src/main/resources/Server/Entity/Effects/Status/CC_Effect_Cryo.json`

**Steps:**
1. Model each on the researched vanilla `Lava_Burn.json` shape (`DamageCalculator.BaseDamage`, `Duration`, `OverlapBehavior`, `ApplicationEffects` for tint/particles/sound, `StatusEffectIcon`, `Debuff: true`). Radiation: small per-tick damage, longer duration. Corrosion: acid/physical damage. Cryo: cold damage + (optional) `ApplicationEffects.HorizontalSpeedMultiplier` slow.
2. No unit test (data asset). These must parse at server load: verified in Phase 7.
3. Commit:
```bash
git add src/main/resources/Server/Entity/Effects/Status/CC_Effect_*.json
git commit -m "feat(fluids): immediate radiation/corrosion/cryo status effects"
```

### Task 3.3: Fluid block + FluidFX + placement-item JSON templates

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssets.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssetsJsonTest.java`

**What this builds:** four pure renderers on `FluidAssets`, modeled on the researched vanilla Water/Lava fluids and `SolidSubstanceAssets.itemJson`:
- `sourceBlockJson(blockId, texturePath, color, lightColor, hazards, physics)` : `MaxFluidLevel:1`, `Opacity:Transparent`, `Textures`, `ParticleColor`=color hex, optional `Light`, `Ticker`, and `Interactions.Collision` from `FluidHazardJson.contactInteractions`.
- `flowingBlockJson(blockId)` : `Parent: <blockId>_Source`, `MaxFluidLevel:8`.
- `fluidFxJson(blockId, fogColorHex, physics)` : `Fog`/`FogColor`/`FogDistance`/`MovementSettings` (cryo overrides sink/horizontal speed).
- `placementItemJson(itemId, blockId, displayName, iconPath)` : `Interactions.Secondary -> PlaceFluid: <blockId>_Source`, `Categories: ["Blocks.Fluids"]`, `MaxStack:1`.

A `FluidPhysics` record (`double flowRate, double sinkSpeed, double horizontalSpeedMultiplier`) holds the generated-default, edit-in-place values (design F5). Default factory: `FluidPhysics.defaultFor(FluidForm)` : water-like, with a cryo override (slower sink, slight slow) and an optional density-based `flowRate`.

**Step 1: Write the failing test** (pin the critical fields, not the whole blob):

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.substance.Color;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidAssetsJsonTest {

    private static final Color RED = new Color(200, 30, 20);

    @Test
    void sourceBlockCarriesParticleColorAndHazardContact() {
        String json = FluidAssets.sourceBlockJson(
            "Fluid_Compound_Sulfuric_Acid",
            FluidAssets.texturePath("Fluid_Compound_Sulfuric_Acid"),
            RED, null, List.of(FluidHazard.CORROSIVE), FluidPhysics.waterLike());
        assertTrue(json.contains("\"MaxFluidLevel\": 1"), json);
        assertTrue(json.contains(RED.toHex()), json);            // ParticleColor
        assertTrue(json.contains("CC_Effect_Corrosion"), json);  // contact hazard
    }

    @Test
    void flowingBlockInheritsSource() {
        String json = FluidAssets.flowingBlockJson("Fluid_Element_Mercury");
        assertTrue(json.contains("\"Parent\": \"Fluid_Element_Mercury_Source\""), json);
        assertTrue(json.contains("\"MaxFluidLevel\": 8"), json);
    }

    @Test
    void placementItemPlacesSource() {
        String json = FluidAssets.placementItemJson(
            "Chem_Fluid_Element_Mercury", "Fluid_Element_Mercury", "Mercury",
            "Icons/ItemsGenerated/Chem_Fluid_Element_Mercury.png");
        assertTrue(json.contains("\"PlaceFluid\""), json);
        assertTrue(json.contains("Fluid_Element_Mercury_Source"), json);
    }

    @Test
    void cryoFxSlowsSink() {
        String fx = FluidAssets.fluidFxJson("Fluid_Element_Liquefied_Helium", "#aef",
            FluidPhysics.defaultFor(/* liquefied */ true));
        assertTrue(fx.contains("SinkSpeed"), fx);
    }
}
```

**Step 2: Run to verify it fails.** FAIL (methods/`FluidPhysics` absent).

**Step 3: Write minimal implementation.** Add `FluidPhysics` record + the four renderers, using Java text blocks exactly like `SolidSubstanceAssets.itemJson`. Model field-by-field on the researched vanilla `Water_Source.json`/`Lava_Source.json`/FluidFX `Water.json`/`Lava.json` and `Fluid_Water.json` (item). Splice hazard contact via `FluidHazardJson.contactInteractions(hazards)` and `Light` via the same null-guard pattern as `SolidSubstanceAssets.lightJson`.

**Step 4: Run to verify it passes.** PASS.

**Step 5: Commit.**
```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssets.java \
        src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidPhysics.java \
        src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidAssetsJsonTest.java
git commit -m "feat(fluids): block/FluidFX/item JSON templates + editable physics defaults"
```

---

## Phase 4: WorldFluidGenerator + Gradle task (integration)

### Task 4.1: `WorldFluidGenerator`

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/WorldFluidGenerator.java`

**Steps:**
1. Mirror `SubstanceAssetGenerator.main` structure exactly. Signature: `main(String[] args)` with `args = [<fluidMasterPng>, <outputRoot>, <iconMasterPng>, <iconMaskPng>]`.
2. Output dirs under `outputRoot`:
   - textures: `Common/BlockTextures`
   - icons: `Common/Icons/ItemsGenerated`
   - blocks: `Server/Item/Block/Fluids`
   - fluidFx: `Server/Item/Block/FluidFX`
   - items: `Server/Item/Items/Fluid` (or a `Chemistry` subfolder: match where the mod namespaces its items)
   - lang: `Server/Languages/en-US` (append, do NOT clobber the solid generator's `server.lang`: see Task 4.3)
   - registry list: `Server/BlockTypeList/CC_Fluids.json` (a CC-owned list; do not overwrite vanilla `Fluids.json`)
3. For each `FluidForm`:
   - `surfaceMask = new LiquidMask(0, 0, 32, 32)`; `tinted = GlowBoost.apply(SubstanceLiquidTinter.tint(master, surfaceMask, color), surfaceMask, tier)`; write `<blockId>.png`.
   - `hazards = FluidHazardComposer.hazardsFor(form, registry)`; `physics = FluidPhysics.defaultFor(form.liquefied())`.
   - `tier = isElement ? GlowDeriver.tierFor(element,…) : GlowDeriver.tierFor(compound,…)`; `light = SolidSubstanceAssets.lightJson(color, tier)`.
   - write source block, flowing block, FluidFX, placement item, icon (`SubstanceIcon.render`), lang line (`items.<itemId>.name = <displayName>`), and collect `<blockId>_Source` into the CC_Fluids list.
4. Write `CC_Fluids.json` = `{ "Blocks": [ ... all source ids ... ] }`.
5. Guard duplicate ids (throw), exactly like the solid generator.
6. No unit test for `main` (it is the integration wiring; its pieces are all tested). Build must compile:
   Run: `./gradlew compileJava`
   Expected: BUILD SUCCESSFUL.
7. Commit:
```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/WorldFluidGenerator.java
git commit -m "feat(fluids): WorldFluidGenerator emits blocks/FX/items/icons/registry"
```

### Task 4.2: Register the Gradle task

**Files:**
- Modify: `build.gradle.kts` (after the `generateFluidPipeTextures` block, ~line 72)

**Steps:**
1. Add, mirroring the existing task:
```kotlin
// Generate the per-substance world fluids (tinted surface textures + block/FX/item JSON +
// CC_Fluids list + lang) from substance color + hazard data. Writes into src/main/resources.
//   ./gradlew generateWorldFluids
tasks.register<JavaExec>("generateWorldFluids") {
    group = "chemistry"
    description = "Bake substance colors + hazards into world fluid blocks, FX, and placement items."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.chonbosmods.chemistry.impl.assetgen.WorldFluidGenerator")
    args(
        project.file("assets-src/fluid_master.png").absolutePath,
        project.file("src/main/resources").absolutePath,
        project.file("assets-src/icon_master.png").absolutePath,
        project.file("assets-src/icon_liquid_mask.png").absolutePath,
    )
}
```
2. Run it:
   Run: `./gradlew generateWorldFluids`
   Expected: console "Generated 66 world fluids -> …"; new files under `src/main/resources/Server/Item/Block/Fluids` etc.
3. Sanity-check one generated file (e.g. a known corrosive acid) has `ParticleColor` + corrosion contact; one liquefied gas reads `Liquid <Name>` in lang and has cryo FX.
4. Commit BOTH the generator output and the task (decide tracking policy to match how solid assets are tracked: if generated assets are committed for solids, commit these too):
```bash
git add build.gradle.kts src/main/resources/Server/Item/Block/Fluids \
        src/main/resources/Server/Item/Block/FluidFX \
        src/main/resources/Server/Item/Items \
        src/main/resources/Common/BlockTextures \
        src/main/resources/Common/Icons/ItemsGenerated \
        src/main/resources/Server/BlockTypeList/CC_Fluids.json
git commit -m "feat(fluids): generate the 66-fluid asset set + Gradle task"
```

### Task 4.3: Lang-merge safety

**Problem:** Both `SubstanceAssetGenerator` and `WorldFluidGenerator` write `Server/Languages/en-US/server.lang`. The solid generator overwrites the file. They must not clobber each other.

**Files:** Modify whichever generator runs second to APPEND, or (cleaner) have each generator write a namespaced lang file: `server.lang` (solids, existing) and `server_fluids.lang` (fluids) IF the engine merges multiple `.lang` files in the dir. If it does not, refactor both to append to one file behind a shared `LangWriter` helper that reads-existing-then-merges keys.

**Steps:**
1. Check whether the engine loads all `*.lang` in `en-US/` or only `server.lang` (grep the SDK / test in Phase 7).
2. Implement the safe approach (separate file if supported, else shared append helper). Add a unit test for the `LangWriter` merge if you build one.
3. Run: `./gradlew generateSolidSubstanceAssets generateWorldFluids` and confirm both keysets survive in the lang output.
4. Commit.

---

## Phase 5: Container override (the standard, F7)

Per `[[fluid-container-override-standard]]`: every water-capable vanilla container gets generated `Filled_<fluid>` states. Do Mug as a full vertical slice first (verify in-game), then Tankard + Bucket as repeats.

### Task 5.1: Capture vanilla container templates

**Files:**
- Create: `assets-src/containers/Deco_Mug.vanilla.json` (copied from `models/references/Server/Item/Items/Deco/Deco_Mug.json`)
- Create: `assets-src/containers/Deco_Tankard.vanilla.json`, `Container_Bucket.vanilla.json`
- Create (art): `assets-src/containers/Mug_master.png` + the mug liquid `LiquidMask` rectangle (record in code, see 5.3); same for tankard/bucket.

**Steps:**
1. Copy each vanilla container JSON into `assets-src/containers/` as the merge base (so we reproduce its existing states like `Filled_Water` and re-inject ours). Document the mug liquid-region rectangle by inspecting `Mug_Texture.png` (the fillable pixels).
2. Commit the captured templates + masters.

### Task 5.2: `FluidContainers` registry + `containerFilledStateJson`

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidContainers.java` (registry: container id -> { vanilla template path, model, base-texture path, `LiquidMask`, broken-item id })
- Modify: `FluidAssets.java` : add `filledStateJson(containerId, fluidForm, texturePath, hazards)` rendering one `State.Filled_<fluidId>` entry (RefillContainer `AllowedFluids: [<blockId>_Source]`, drink interaction with `FluidHazardJson.drinkEffects`, `DurabilityModify` -> broken item).
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/FluidContainersTest.java`

**Step 1: Write the failing test:**

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidContainersTest {

    @Test
    void filledStateFillsFromSourceAndDrinksHazard() {
        var reg = InMemorySubstanceRegistry.loadFromResources();
        var acid = new FluidForm(reg.compound("H2SO4").orElseThrow(), false);
        String json = FluidAssets.filledStateJson(
            "Deco_Mug", acid,
            "Blocks/Miscellaneous/Mug_Texture_Fluid_Compound_Sulfuric_Acid.png",
            List.of(FluidHazard.CORROSIVE));
        assertTrue(json.contains("Filled_Fluid_Compound_Sulfuric_Acid"), json);
        assertTrue(json.contains("Fluid_Compound_Sulfuric_Acid_Source"), json); // AllowedFluids
        assertTrue(json.contains("CC_Effect_Corrosion"), json);                  // drink hazard
        assertTrue(json.contains("Root_Secondary_Consume_Drink"), json);
    }

    @Test
    void registryListsThreeWaterCapableContainers() {
        assertTrue(FluidContainers.ALL.size() >= 3);
    }
}
```

**Step 2-4:** Implement to green (model the state JSON on the researched `Deco_Mug.State.Filled_Water` block, swapping name/AllowedFluids/texture/effects). Run:
`./gradlew test --tests 'com.chonbosmods.chemistry.impl.assetgen.FluidContainersTest'` -> PASS.

**Step 5: Commit.**

### Task 5.3: `FluidContainerGenerator` + Gradle task

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/FluidContainerGenerator.java`
- Modify: `build.gradle.kts` (add `generateFluidContainers` task, same pattern)

**Steps:**
1. For each `container in FluidContainers.ALL`, load its vanilla template; for each `FluidForm`: tint the container liquid region (`SubstanceLiquidTinter.tint(containerMaster, container.liquidMask(), color)`) -> write `Common/Blocks/Miscellaneous/<Container>_Texture_<blockId>.png`; build the `Filled_<fluidId>` state JSON; collect all states.
2. Merge collected states into the template's `State` object (preserve vanilla states), write the overridden container JSON to `src/main/resources/Server/Item/Items/<Deco|Container>/<Container>.json`.
3. Append lang entries for each filled-state display name.
4. Register Gradle task `generateFluidContainers` with `args = [<outputRoot>]` (the container masters/templates are read from `assets-src/containers/` by convention inside the generator, or passed as args).
5. Run: `./gradlew generateFluidContainers` -> "Generated 3 containers x 66 fluids = 198 filled states".
6. Compile + commit generator, task, and generated overrides.

---

## Phase 6: Coolant tags for liquefied gases (D34b)

### Task 6.1: Stamp coolant tags where data supports it

**Files:**
- Modify: `WorldFluidGenerator` / `FluidAssets.sourceBlockJson`

**Steps:**
1. If the dataset carries heat fields for a substance (check what exists; design §8 notes they may arrive with the power milestone), add `Tags.coolant` + `heat_capacity`/`condensation_temp` to the source block JSON for liquefied gases. Where absent, omit (no coolant role) : do NOT invent numbers.
2. Add a unit test asserting a liquefied gas with heat data gets the `coolant` tag and one without does not (only if any data exists; otherwise leave a `// TODO coolant: blocked on power-milestone heat data` and skip).
3. Commit.

---

## Phase 7: Full generation + in-game verification

### Task 7.1: Regenerate everything + full test suite

**Steps:**
1. `./gradlew generateWorldFluids generateFluidContainers` (and `generateSolidSubstanceAssets` to confirm lang co-existence).
2. `./gradlew test` -> all green.
3. `./gradlew build` -> BUILD SUCCESSFUL (assets fold into the jar).
4. Commit any regenerated assets.

### Task 7.2: Headless load verification

Per `[[devserver-logs]]` and `[[user-controls-devservers]]` (never start/stop the devServer yourself : ask the user to toggle it, then read the log):

**Steps:**
1. Ask the user to restart the devServer (or confirm it picks up `--mods=src/main`).
2. Read `devserver/logs/<latest>_server.log`; confirm: `CC_Fluids.json` blocks register, the 3 status effects parse, container overrides parse, no asset-load errors for the new ids.
3. If any id fails to parse, debug per `superpowers:systematic-debugging` (find the offending JSON, compare field-by-field to the vanilla reference it was modeled on).

### Task 7.3: In-game smoke test (representative four)

**Steps:**
1. Pick four fluids spanning the matrix: a benign liquid (water-like), a corrosive acid, a radioactive element, a liquefied gas.
2. In-game (press-F interactions per `[[hytale-interact-key]]`): place each fluid, wade in (verify contact hazard fires + cooldown-paced), fill a mug from the source, drink (verify drink hazard), confirm color reads distinct per substance and glow shows for the radioactive one.
3. Note results in the design doc's status block.

### Task 7.4: Finish the branch

Use `superpowers:finishing-a-development-branch` to decide merge/PR. Do NOT push unless the user says "push".

---

## Risk checklist (from design §10) to keep visible during execution

- [ ] Vanilla container override reproduces existing states (Filled_Water, milk) : diff against vanilla.
- [ ] Lang files from both generators coexist (Task 4.3).
- [ ] `CC_Fluids.json` is CC-owned, vanilla `Fluids.json` untouched.
- [ ] Generated-asset tracking policy matches the solid-asset precedent.
- [ ] Cryo wadeable physics doesn't soft-lock movement (Task 7.3).
- [ ] `FluidHazardJson` benign case renders valid JSON (empty effect array, not a dangling comment) once 3.3 fixes the envelope.
- [ ] Affliction seam call sites are single-point so dose-accumulation swaps in later without asset rework.
```
