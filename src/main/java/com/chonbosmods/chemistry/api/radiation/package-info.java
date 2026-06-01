/**
 * Radiation contracts (design doc &sect;4.1, &sect;5): the {@code RadiationSource} interface
 * (one intensity plus the single penetrating-vs-contact-only bit) and the contract for
 * reading a post-distance, post-shielding dose at an entity's position. A third party can
 * implement {@code RadiationSource} on its own block and have it work with the Geiger and
 * the affliction model without touching any of our numbers. Falloff curves, band thresholds,
 * and tick budgets are decided in {@code impl}, never here.
 */
package com.chonbosmods.chemistry.api.radiation;
