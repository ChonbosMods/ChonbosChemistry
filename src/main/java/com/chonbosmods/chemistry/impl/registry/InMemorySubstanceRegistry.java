package com.chonbosmods.chemistry.impl.registry;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.DecayMode;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Isotope;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * In-memory {@link SubstanceRegistry}: loads the bundled {@code data/} resources once via the
 * {@code api.substance} codecs and indexes them into maps. The contract is backing-agnostic, so
 * this can later be replaced by an {@code AssetStore}-backed implementation without touching callers.
 */
public final class InMemorySubstanceRegistry implements SubstanceRegistry {

    private static final String[] ISOTOPE_RESOURCES = {
        "/isotopes/batchA_Z1-20.json",
        "/isotopes/batchB_Z21-40.json",
        "/isotopes/batchC_Z41-60.json",
        "/isotopes/batchD_Z61-80.json",
        "/isotopes/batchE_Z81-100.json",
        "/isotopes/batchF_Z101-118.json",
    };

    private final List<Element> elements = new ArrayList<>();
    private final List<Compound> compounds = new ArrayList<>();
    private final List<Isotope> isotopes = new ArrayList<>();
    private final Map<String, Element> elementsBySymbol = new HashMap<>();
    private final Map<Integer, Element> elementsByAtomicNumber = new HashMap<>();
    private final Map<String, Compound> compoundsByFormula = new HashMap<>();
    private final Map<Map<String, Integer>, Compound> compoundsByComposition = new HashMap<>();
    private final Map<String, Isotope> isotopesBySymbol = new HashMap<>();
    private final Map<Long, Isotope> isotopesByCoordinates = new HashMap<>();

    private InMemorySubstanceRegistry() {
    }

    /** Loads and indexes the bundled chemistry data. */
    public static InMemorySubstanceRegistry loadFromResources() {
        InMemorySubstanceRegistry registry = new InMemorySubstanceRegistry();
        for (Element element : decodeArray("/elements.json", Element.CODEC, Element[]::new)) {
            registry.addElement(element);
        }
        for (Compound compound : decodeArray("/compounds.json", Compound.CODEC, Compound[]::new)) {
            registry.addCompound(compound);
        }
        for (String resource : ISOTOPE_RESOURCES) {
            for (Isotope isotope : decodeArray(resource, Isotope.CODEC, Isotope[]::new)) {
                registry.addIsotope(isotope);
            }
        }
        return registry;
    }

    private void addElement(Element element) {
        elements.add(element);
        elementsBySymbol.put(element.formula(), element);
        elementsByAtomicNumber.put(element.atomicNumber(), element);
    }

    private void addCompound(Compound compound) {
        compounds.add(compound);
        compoundsByFormula.put(compound.formula(), compound);
        compoundsByComposition.putIfAbsent(compound.composition(), compound); // first wins for isomers
    }

    private void addIsotope(Isotope isotope) {
        isotopes.add(isotope);
        isotopesBySymbol.put(isotope.isotopeSymbol(), isotope);
        isotopesByCoordinates.put(coordinateKey(isotope.parentZ(), isotope.massNumber()), isotope);
    }

    private static long coordinateKey(int atomicNumber, int massNumber) {
        return ((long) atomicNumber << 20) | (massNumber & 0xFFFFFL);
    }

    private static <T> T[] decodeArray(String resource, Codec<T> codec, IntFunction<T[]> arrayFactory) {
        try (InputStream in = InMemorySubstanceRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled chemistry resource: " + resource);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new ArrayCodec<>(codec, arrayFactory).decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load chemistry resource: " + resource, e);
        }
    }

    @Override
    public Optional<Element> element(String symbol) {
        return Optional.ofNullable(elementsBySymbol.get(symbol));
    }

    @Override
    public Optional<Element> element(int atomicNumber) {
        return Optional.ofNullable(elementsByAtomicNumber.get(atomicNumber));
    }

    @Override
    public Optional<Compound> compound(String formula) {
        return Optional.ofNullable(compoundsByFormula.get(formula));
    }

    @Override
    public Optional<Isotope> isotope(String symbol) {
        return Optional.ofNullable(isotopesBySymbol.get(symbol));
    }

    @Override
    public Optional<Isotope> isotope(int atomicNumber, int massNumber) {
        return Optional.ofNullable(isotopesByCoordinates.get(coordinateKey(atomicNumber, massNumber)));
    }

    @Override
    public Collection<Element> elements() {
        return Collections.unmodifiableList(elements);
    }

    @Override
    public Collection<Compound> compounds() {
        return Collections.unmodifiableList(compounds);
    }

    @Override
    public Collection<Isotope> isotopes() {
        return Collections.unmodifiableList(isotopes);
    }

    @Override
    public List<Isotope> isotopesOf(Element element) {
        List<Isotope> resolved = new ArrayList<>();
        for (String symbol : element.isotopeSymbols()) {
            Isotope isotope = isotopesBySymbol.get(symbol);
            if (isotope != null) {
                resolved.add(isotope);
            }
        }
        return resolved;
    }

    @Override
    public Map<Element, Integer> constituentsOf(Compound compound) {
        Map<Element, Integer> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : compound.composition().entrySet()) {
            Element element = elementsBySymbol.get(entry.getKey());
            if (element != null) {
                resolved.put(element, entry.getValue());
            }
        }
        return resolved;
    }

    @Override
    public List<Isotope> daughtersOf(Isotope isotope) {
        LinkedHashSet<Isotope> resolved = new LinkedHashSet<>();
        for (DecayMode mode : isotope.decayModes()) {
            Isotope daughter = isotopesBySymbol.get(mode.daughter());
            if (daughter != null) {
                resolved.add(daughter);
            }
        }
        return new ArrayList<>(resolved);
    }

    @Override
    public Optional<Compound> compoundByComposition(Map<String, Integer> composition) {
        return Optional.ofNullable(compoundsByComposition.get(composition));
    }
}
