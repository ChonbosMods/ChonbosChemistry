/**
 * Substance data-model contracts (design doc &sect;3): the shared substance base plus the
 * element, isotope, and compound schemas. Defines the few true authored inputs (elements,
 * isotopes) and the shape of everything derived from them (molar mass, compound radioactivity,
 * compound toxicity). No concrete substance data lives here: only the schema and accessors.
 */
package com.chonbosmods.chemistry.api.substance;
