package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Toxicity;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a fluid to the hazards its substance data triggers (design F4, F5). Hazards stack: every
 * trigger that fires contributes its module. Returned in {@link FluidHazard} declaration order,
 * deduped. Element radioactivity is derived from isotope stability via {@link GlowDeriver}; the
 * {@code PropertyFlags}/{@code Toxicity} triggers are compound-only (elements lack those fields).
 *
 * <p>The toxicity trigger keys off a non-empty exposure {@code Route}, not mere object presence.
 * {@code Compound.toxicity()} is nullable, and where present it frequently carries an empty route
 * (water included): a presence-check would over-trigger CORROSIVE. A populated exposure route is
 * the real signal of modeled toxicity; {@link #isToxic} null-guards the nullable case.
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
                || isToxic(c.toxicity());
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

    /** A compound is toxic when its toxicity lens names at least one exposure route. */
    private static boolean isToxic(Toxicity toxicity) {
        return toxicity != null && !toxicity.route().isEmpty();
    }
}
