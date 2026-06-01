/**
 * Public API surface of Chonbo's Chemistry: interfaces, schema, tags, and
 * registry/payload/containment contracts that third parties build against.
 *
 * <p><b>Governing rule (design doc &sect;2.1): {@code api} defines, {@code impl} decides.</b>
 * Nothing in this package or its subpackages may contain a concrete gameplay value,
 * a balance number, or content. The API says <i>"a radiation source has an intensity
 * and a penetrating flag, and here is how to read a dose at a position"</i>; the
 * {@code impl} layer decides the falloff curve, the band thresholds, and the tick budget.
 *
 * <p>This boundary is the constraint that quietly erodes over time, so it is enforced
 * as the topmost package split. {@code api} must never import from
 * {@code com.chonbosmods.chemistry.impl}. Because this surface is semantically versioned
 * for third-party consumers, additive changes are cheap and signature changes are expensive.
 */
package com.chonbosmods.chemistry.api;
