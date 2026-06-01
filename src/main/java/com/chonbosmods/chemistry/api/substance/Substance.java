package com.chonbosmods.chemistry.api.substance;

/**
 * Shared base for every element and compound (design doc §3.1). Carries the attributes common to
 * all substances: identity, phase, the generative color, density, and molar mass.
 *
 * <p>Sealed to {@link Element} and {@link Compound}: the substance taxonomy is closed and
 * foundational. Extension is data-driven (new elements/compounds as JSON) and interface-driven
 * (radiation/payload/containment contracts), not via new substance subtypes. Sealed is also the
 * semver-safe direction (sealed→unsealed is a non-breaking relaxation if a real need appears).
 *
 * <p>An abstract class rather than an interface because it holds state; setters are package-private
 * so only the codecs in this package drive them ("pure data" relative to a package-mate codec).
 */
public sealed abstract class Substance permits Element, Compound {

    private String name;
    private String formula;
    private Phase phase;
    private Color color;
    private Double density;
    private double molarMass;

    public String name() {
        return name;
    }

    /** Symbol for an element ({@code Fe}), chemical formula for a compound ({@code H2O}). */
    public String formula() {
        return formula;
    }

    public Phase phase() {
        return phase;
    }

    public Color color() {
        return color;
    }

    /** Nullable: not all substances carry a density. */
    public Double density() {
        return density;
    }

    /** Authored standard atomic weight for an element; derived from composition for a compound. */
    public double molarMass() {
        return molarMass;
    }

    void setName(String name) {
        this.name = name;
    }

    void setFormula(String formula) {
        this.formula = formula;
    }

    void setPhase(Phase phase) {
        this.phase = phase;
    }

    void setColor(Color color) {
        this.color = color;
    }

    void setDensity(Double density) {
        this.density = density;
    }

    void setMolarMass(double molarMass) {
        this.molarMass = molarMass;
    }
}
