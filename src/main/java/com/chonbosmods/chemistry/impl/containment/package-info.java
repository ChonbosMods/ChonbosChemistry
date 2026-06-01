/**
 * The containment implementation (design doc &sect;6): the concrete sealed-volume topology
 * engine and gas system. Flood-fills airspaces, recomputes incrementally only when a boundary
 * block changes, caps volume size, and drives the runtime trigger volumes plus client-side
 * particle density that represent gases in the world. Realizes the
 * {@link com.chonbosmods.chemistry.api.containment} contracts.
 */
package com.chonbosmods.chemistry.impl.containment;
