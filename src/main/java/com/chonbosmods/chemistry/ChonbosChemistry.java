package com.chonbosmods.chemistry;

import com.chonbosmods.chemistry.api.registry.Chemistry;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;

/**
 * Chonbo's Chemistry: foundational chemistry library for Hytale.
 *
 * <p>A shared substance data model, a radiation engine, and a payload-agnostic
 * containment/affliction system, exposed through a clean API that an
 * interconnected family of chemistry mods builds on.
 *
 * <p>The codebase is split into two enforced top-level packages:
 * <ul>
 *   <li>{@code com.chonbosmods.chemistry.api}: interfaces, schema, and contracts.
 *       Defines; never decides. Zero gameplay, zero content, zero concrete values.</li>
 *   <li>{@code com.chonbosmods.chemistry.impl}: the engine, content, gear, instruments,
 *       and concrete values that implement those contracts.</li>
 * </ul>
 * This bootstrap lives in the package root because it bridges both halves; it is the
 * only class permitted to do so.
 */
public class ChonbosChemistry extends JavaPlugin {

    private static ChonbosChemistry instance;

    public ChonbosChemistry(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static ChonbosChemistry getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        getLogger().atInfo().log("Chonbo's Chemistry setting up...");
        InMemorySubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        Chemistry.set(registry);
        getLogger().atInfo().log("Loaded " + registry.elements().size() + " elements, "
            + registry.compounds().size() + " compounds, " + registry.isotopes().size() + " isotopes.");
    }

    @Override
    protected void start() {
        getLogger().atInfo().log("Chonbo's Chemistry v" + getManifest().getVersion() + " started!");
    }

    @Override
    protected void shutdown() {
        Chemistry.clear();
        getLogger().atInfo().log("Chonbo's Chemistry shutting down...");
    }
}
