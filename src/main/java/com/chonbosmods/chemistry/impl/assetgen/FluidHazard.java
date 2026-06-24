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
