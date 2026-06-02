package com.chonbosmods.chemistry.api.registry;

import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Isotope;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime access to the loaded substance data: lookup, resolution of the raw symbol references the
 * beans keep, and reverse lookup. Backing-agnostic by design (currently in-memory; an
 * {@code AssetStore}-backed implementation can replace it later without touching consumers).
 *
 * <p>Category queries ("all fissile") and asset tags are deliberately omitted until a consumer
 * needs them; iterate {@link #elements()}/{@link #compounds()}/{@link #isotopes()} and filter.
 */
public interface SubstanceRegistry {

    // --- lookup ---

    Optional<Element> element(String symbol);

    /** By atomic number; supports transmutation (Z±1 -> neighbour element). */
    Optional<Element> element(int atomicNumber);

    Optional<Compound> compound(String formula);

    Optional<Isotope> isotope(String symbol);

    /**
     * By nuclide-chart coordinates. The single primitive for nuclear transitions: neutron capture
     * is {@code (Z, A+1)}, beta-minus {@code (Z+1, A)}, alpha {@code (Z-2, A-4)}.
     */
    Optional<Isotope> isotope(int atomicNumber, int massNumber);

    /** All elements, unmodifiable. */
    Collection<Element> elements();

    /** All compounds, unmodifiable. */
    Collection<Compound> compounds();

    /** All isotopes, unmodifiable. */
    Collection<Isotope> isotopes();

    // --- resolution (beans keep raw symbols; the registry resolves them to objects) ---

    /** Resolves {@link Element#isotopeSymbols()} to objects; unresolved symbols are skipped. */
    List<Isotope> isotopesOf(Element element);

    /** Resolves a compound's composition to {@link Element}s + counts; unresolved symbols are skipped. */
    Map<Element, Integer> constituentsOf(Compound compound);

    /** Resolves an isotope's decay-mode daughters to objects (distinct); unresolved symbols are skipped. */
    List<Isotope> daughtersOf(Isotope isotope);

    // --- reverse lookup ---

    /** Finds a compound by its element-count composition (for synthesis). */
    Optional<Compound> compoundByComposition(Map<String, Integer> composition);
}
