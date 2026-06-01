/**
 * The radiation engine (design doc &sect;4, &sect;5): the concrete implementation of dose rate
 * (&micro;Sv/h) and accumulated dose (mSv), the four exposure vectors (proximity field,
 * carried, seepage, ambient zone), distance falloff, block shielding, isotope decay over
 * half-life, and the configurable safe/elevated/dangerous/lethal bands. This is where the
 * numbers the {@code api} radiation contracts leave open are decided.
 */
package com.chonbosmods.chemistry.impl.radiation;
