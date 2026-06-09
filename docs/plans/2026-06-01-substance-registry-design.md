> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# SubstanceRegistry Contract Design

**Date:** 2026-06-01
**Status:** Validated, ready to implement
**Scope:** The `com.chonbosmods.chemistry.api.registry` contract for looking up and navigating the substance data at runtime. Backed by Hytale's `AssetStore` (implementation detail, hidden behind the contract).

---

## 1. Purpose

`api.substance` turns JSON into typed objects. The registry is the runtime access layer above it: it loads the data and lets code find and navigate substances. Hytale's `AssetStore` already provides raw "get asset by id"; this contract adds the three things AssetStore doesn't know about chemistry:

1. **Lookup** in chemistry terms (by symbol/formula, element by atomic number, isotope by nuclide coordinates).
2. **Resolution** of the raw symbol strings the beans deliberately kept (isotope lists, composition maps, decay daughters → objects).
3. **Reverse lookup** (composition → compound) for synthesis.

Category queries ("all fissile") and asset tags are **deliberately deferred** until a real consumer exists. Tag-based recipe interop will use Hytale's native asset tag system (added at load when the first downstream recipe needs it), not Java query methods.

## 2. Backing: Hytale AssetStore (committed)

The implementation registers `Element`/`Compound`/`Isotope` as custom asset stores via `AssetRegistry.register(HytaleAssetStore.builder(...).setCodec(...).setKeyFunction(...))`, reusing the `BuilderCodec`s from `api.substance`. This buys third-party extension and override (drop JSON in an asset pack), hot-reload, and asset-pipeline integration for free: the design's §8.1 wishlist, met natively rather than reinvented.

The **contract stays backing-agnostic**: consumers code against `SubstanceRegistry`, never against `AssetStore`. This is the payoff of the api/impl split: the backing can change without touching consumers, and Hytale's volatile asset types stay behind `api.shim`.

**Open implementation question (resolve first, via a spike):** Hytale asset stores appear to be one-file-per-asset. Confirm the load model against the 0.5.3 source. If per-file, the implementation step includes splitting the array files (`elements.json` = 118 records) into per-substance files under `Server/…/`, and setting `IncludesAssetPack: true`. The contract is unaffected either way.

## 3. The contract

```java
package com.chonbosmods.chemistry.api.registry;

public interface SubstanceRegistry {

    // --- lookup ---
    Optional<Element>  element(String symbol);                     // "Fe" -> Iron
    Optional<Element>  element(int atomicNumber);                  // 26 -> Iron; transmuter Z±1
    Optional<Compound> compound(String formula);                   // "H2O" -> Water
    Optional<Isotope>  isotope(String symbol);                     // "U-235"
    Optional<Isotope>  isotope(int atomicNumber, int massNumber);  // nuclide-chart coordinates

    Collection<Element>  elements();    // full sets, unmodifiable; iterate + ad-hoc filter
    Collection<Compound> compounds();
    Collection<Isotope>  isotopes();

    // --- resolution (beans keep raw symbols; the registry resolves them) ---
    List<Isotope>          isotopesOf(Element element);            // isotopeSymbols -> objects
    Map<Element, Integer>  constituentsOf(Compound compound);      // composition -> Elements + counts
    List<Isotope>          daughtersOf(Isotope isotope);           // decay-mode daughters -> objects

    // --- reverse lookup ---
    Optional<Compound> compoundByComposition(Map<String, Integer> composition);  // synthesizer
}
```

### Semantics
- **`Optional` for lookups.** A miss (typo, or a third-party reference to an unloaded substance) is a normal condition the caller handles, not a crash.
- **Dangling references skip.** `isotopesOf`/`daughtersOf`/`constituentsOf` return only symbols that resolve; the impl logs a warning. (Our data has zero orphans; third-party data might dangle.)
- **Single-symbol resolution reuses the lookups.** `DecayMode.daughter()` / `Isotope.parentSymbol()` are just `isotope(...)` / `element(...)`; only the *collection* resolutions need dedicated methods.

## 4. Access

A static accessor backed by a holder the impl sets at startup and clears on shutdown:

```java
SubstanceRegistry reg = Chemistry.substances();
```

Matches how Hytale assets are reached (statically), so it feels native, and honors §8.1's "no orphaned static state": populated in `setup()`, nulled in `shutdown()`, so hot-reload swaps it cleanly. The accessor lives in a small `Chemistry` holder class (interfaces can't hold the mutable field).

## 5. Machine alignment

The lookup/navigation surface is sized to the planned machines (synthesizer, decomposer, converter, transmuter, irradiator, decay vault):

| Machine | Operation | Contract support |
|---|---|---|
| Transmuter | swap Z | `element(int atomicNumber)` (Z±1) |
| Irradiator | activate (n-capture Z,A → Z,A+1) | `isotope(int, int)` coordinate lookup |
| Decay vault | harvest (decay over time) | `daughtersOf(isotope)`; vault walks the chain step-by-step |
| Synthesizer | combine → compound | `compoundByComposition(map)` |
| Decomposer | break → elements | `constituentsOf(compound)` |
| Converter | phase change | melt/boil on beans + plain lookup |

Every nuclear transition is a move on the nuclide chart by coordinates, so `isotope(int atomicNumber, int massNumber)` is the single primitive that serves activation, decay, and isotope-level transmutation.

## 6. Deferred (until a consumer exists)
- Category queries (`fissileIsotopes()` etc.) — trivial stream-filters over the collections when a UI/command needs them.
- Asset tags (`chonbo:fissile`) — for recipe/cross-mod interop; added at load via AssetStore when the first downstream recipe needs them.
- `decayChainOf(Isotope)` full-tree traversal — the decay vault walks the chain dynamically via `daughtersOf` one step at a time; add the precomputed tree only if a consumer wants it whole.

## 7. Testing (TDD)
- Lookup hits/misses (`Optional` present/empty).
- Resolution round-trips against real data: `isotopesOf(Iron)` returns Fe-54…Fe-60; `constituentsOf(water)` = {H:2, O:1}; `daughtersOf(U-238)` includes Th-234.
- Nuclide coordinate lookup: `isotope(27, 60)` = Co-60 (the irradiator's activation target from Co-59).
- `compoundByComposition({H:2, O:1})` = Water.
- Dangling reference (a bean referencing an unloaded symbol) skips without throwing.
