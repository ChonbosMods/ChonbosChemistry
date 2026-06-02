package com.chonbosmods.chemistry.api.registry;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import org.junit.jupiter.api.Test;

class ChemistryTest {

    @Test
    void setExposesRegistryAndClearRemovesIt() {
        Chemistry.clear();
        assertThrows(IllegalStateException.class, Chemistry::substances);

        SubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        Chemistry.set(registry);
        assertSame(registry, Chemistry.substances());

        Chemistry.clear();
        assertThrows(IllegalStateException.class, Chemistry::substances);
    }
}
