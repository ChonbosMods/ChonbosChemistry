package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Isotope;
import com.chonbosmods.chemistry.api.substance.Stability;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure derivation of a substance's {@link GlowTier} from nuclear data already in the registry.
 * No data-schema additions: elements key off their isotope list, compounds off
 * {@code isRadioactive} plus constituent elements and any nuclide referenced in name/notes.
 */
public final class GlowDeriver {

    /** Geologic threshold: longest-lived isotope at/above this half-life reads as FAINT (Th, U). */
    static final double FAINT_HALF_LIFE_SECONDS = 1e16;
    /** Synthetic short-lived superheavies (Fm and up) read as FIERCE. */
    static final int FIERCE_MIN_Z = 100;

    private static final Pattern ISOTOPE_REF = Pattern.compile("\\b([A-Z][a-z]?-\\d{1,3})\\b");

    /**
     * Chemiluminescent glow exceptions: visual glow without radioactivity. Currently white
     * phosphorus only (user decision 2026-06-05: the element is named light-bearer and white P
     * glows in the dark). Checked first in {@link #tierFor(Element, SubstanceRegistry)}, before
     * the stable-isotope short-circuit, so it overrides the nuclear derivation.
     */
    private static final Map<String, GlowTier> CHEMILUMINESCENT = Map.of("P", GlowTier.FAINT);

    private GlowDeriver() {}

    /** Derives the glow tier of an element from its isotope list. */
    public static GlowTier tierFor(Element element, SubstanceRegistry registry) {
        GlowTier override = CHEMILUMINESCENT.get(element.formula());
        if (override != null) {
            return override;
        }
        List<Isotope> isotopes = registry.isotopesOf(element);
        if (isotopes.isEmpty() || isotopes.stream().anyMatch(i -> i.stability() == Stability.STABLE)) {
            return GlowTier.NONE;
        }
        if (element.atomicNumber() >= FIERCE_MIN_Z) {
            return GlowTier.FIERCE;
        }
        double longest = isotopes.stream()
            .map(Isotope::halfLifeSeconds)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0);
        return longest >= FAINT_HALF_LIFE_SECONDS ? GlowTier.FAINT : GlowTier.STRONG;
    }

    /**
     * Derives the glow tier of a compound from its radioactivity flag, its constituent elements,
     * and any nuclide referenced in its name/notes. Radioactive compounds with nothing resolvable
     * fall back to {@link GlowTier#STRONG}: be loud rather than silent (design section 4).
     */
    public static GlowTier tierFor(Compound compound, SubstanceRegistry registry) {
        if (!compound.isRadioactive()) {
            return GlowTier.NONE;
        }
        GlowTier best = GlowTier.NONE;
        for (String symbol : compound.composition().keySet()) {
            GlowTier t = registry.element(symbol).map(e -> tierFor(e, registry)).orElse(GlowTier.NONE);
            best = best.compareTo(t) >= 0 ? best : t;
        }
        String text = compound.name() + " " + (compound.notes() == null ? "" : compound.notes());
        Matcher m = ISOTOPE_REF.matcher(text);
        while (m.find()) {
            GlowTier t = registry.isotope(m.group(1)).map(GlowDeriver::isotopeTier).orElse(GlowTier.NONE);
            best = best.compareTo(t) >= 0 ? best : t;
        }
        // radioactive but nothing resolvable: be loud rather than silent (design section 4)
        return best == GlowTier.NONE ? GlowTier.STRONG : best;
    }

    // A compound reaches FIERCE only via a Z>=100 constituent element (see element tierFor),
    // never via a notes-referenced nuclide: isotopeTier caps at STRONG by design.
    private static GlowTier isotopeTier(Isotope isotope) {
        if (isotope.stability() == Stability.STABLE) {
            return GlowTier.NONE;
        }
        Double halfLife = isotope.halfLifeSeconds();
        return halfLife != null && halfLife >= FAINT_HALF_LIFE_SECONDS ? GlowTier.FAINT : GlowTier.STRONG;
    }
}
