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
