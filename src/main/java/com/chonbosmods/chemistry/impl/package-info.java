/**
 * Implementation layer of Chonbo's Chemistry: the engine and content that realize the
 * {@link com.chonbosmods.chemistry.api} contracts.
 *
 * <p>Substances and their generated forms, color-driven generation, the radiation engine,
 * gear and instruments, the containment implementation, the periodic table, and the basic
 * machines plus baseline recipes all live here. This is where concrete values, balance
 * numbers, and gameplay decisions belong.
 *
 * <p>Per the governing rule (design doc &sect;2.1), {@code impl} <i>decides</i> what {@code api}
 * merely <i>defines</i>. This layer depends freely on {@code api} and churns underneath the
 * stable API surface; the reverse dependency is forbidden.
 */
package com.chonbosmods.chemistry.impl;
