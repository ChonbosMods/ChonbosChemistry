/**
 * Containment-service contracts (design doc &sect;6.3): the sealed-volume primitive and the
 * pluggable-payload registration surface. A {@code SealedVolume} is a connected airspace
 * found by flood-fill, bounded by sealing-tagged blocks, tracking its cell set, boundary,
 * sealed state, and a generic contents map. Oxygen, radiation, and toxic gas register as
 * payload types against one topology engine. Breach is the whole mechanic: sealed holds,
 * breached bleeds to zero. Reusable as a standalone service outside the foundation.
 */
package com.chonbosmods.chemistry.api.containment;
