/**
 * Material forms and generation (design doc &sect;7): color-driven generation of item, block,
 * fluid, and GUI-gauge forms from a small set of grayscale template textures tinted by each
 * substance's color. One metallic-gray ingot template times ~80 element colors yields ~80
 * ingots with zero hand-painting. Owns the forms matrix, the unit-accounting backbone
 * (nugget=1, ingot=9, plate=9, dust=9, block=81), and the no-orphan-items guarantee.
 */
package com.chonbosmods.chemistry.impl.forms;
