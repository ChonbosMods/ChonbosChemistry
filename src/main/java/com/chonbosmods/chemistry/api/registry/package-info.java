/**
 * Registry, lookup, and tag contracts (design doc &sect;8): register and query any substance,
 * isotope, or compound, and query tags such as "all fissile," "all corrosive," or
 * "all penetrating emitters." Registry-based and hot-reload-safe by contract so the stateful
 * gas/containment simulation survives runtime reloads without orphaning volumes.
 */
package com.chonbosmods.chemistry.api.registry;
