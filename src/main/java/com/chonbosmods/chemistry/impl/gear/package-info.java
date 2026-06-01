/**
 * Gear and instruments (design doc &sect;5.4, &sect;5.6): the engine's other half, not machines.
 * Instruments (Geiger counter, dosimeter/badge, spectrometer, contamination meter) read the
 * data the library already stores. Protective gear (rad suit, lead armor, lead chest/backpack)
 * rides the same shielding math the Geiger respects, so rate reduction is never immunity:
 * stay long enough and you still accumulate a dose.
 */
package com.chonbosmods.chemistry.impl.gear;
