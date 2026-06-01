/**
 * Shared affliction-model contracts (design doc &sect;4.4, &sect;5.5): the exposure-payload
 * interface and the affliction lifecycle (dose in &rarr; threshold-tiered effects &rarr;
 * antidote clears). Radiation poisoning and chemical toxicity are the same accumulating-harm
 * mechanism with different payloads, so both ride this one model. Tier thresholds and the
 * treatment contract's concrete values are decided in {@code impl}.
 */
package com.chonbosmods.chemistry.api.affliction;
