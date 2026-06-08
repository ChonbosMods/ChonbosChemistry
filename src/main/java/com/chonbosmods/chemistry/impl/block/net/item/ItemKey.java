package com.chonbosmods.chemistry.impl.block.net.item;

/**
 * The minimal identity a routing decision needs about a discrete item: its type {@code id} and a
 * {@code count} (2026-06-06 item-channel design §13.4). Deliberately free of any engine
 * {@code ItemStack}: the pure transport layer (filters, pathfinder, extraction eligibility) reasons
 * only over what it must, and the engine glue converts to/from real stacks at the boundary.
 *
 * <p>Item metadata stays opaque and lives one layer up on the {@code TravelingStack} (carried as a
 * raw BSON document so engine stacks round-trip); it is intentionally absent here because no routing
 * decision branches on it.
 */
public record ItemKey(String id, int count) {}
