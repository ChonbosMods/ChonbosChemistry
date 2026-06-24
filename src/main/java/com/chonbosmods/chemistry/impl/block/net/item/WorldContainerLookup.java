package com.chonbosmods.chemistry.impl.block.net.item;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Live {@link ContainerLookup} over a {@link World}: bridges the pure ITEM transport layer to the engine
 * {@code ItemContainerBlock} block-entity component (verified in the 2026-06-06 design "Spike findings
 * (Task 7)"). It is the thin world adapter behind the {@link ContainerLookup} seam; all routing logic
 * stays in the pure layer.
 *
 * <h2>Container access path (spike-verified, NOT the stale §16.6 claims)</h2>
 * The HyProTech {@code MachineItemAccess} path ({@code ItemContainerState}/{@code ItemContainerBlockState}/
 * {@code BlockStateModule}) DOES NOT EXIST in Server 0.5.3. The container moved to a plain {@code ChunkStore}
 * block-entity COMPONENT, resolved exactly like {@link com.chonbosmods.chemistry.impl.block.net.PipeNode}:
 * {@code BlockModule.getBlockEntity(world, x, y, z)} then {@code store.getComponent(ref, type)} where
 * {@code type == ItemContainerBlock.getComponentType()}. {@link ItemContainerBlock#getItemContainer()} is
 * {@code @Nonnull} (it lazily allocates a {@code SimpleItemContainer(capacity)} on first call), so a present
 * component always yields a usable {@link ItemContainer}. Component presence IS the "this block has a
 * container" test: vanilla chests declare the very same component in their BlockType JSON.
 *
 * <h2>Insert / extract against the engine {@link ItemContainer}</h2>
 * <ul>
 *   <li><b>Insert commit:</b> {@code addItemStack(stack, false, false, false)} returns an
 *       {@link ItemStackTransaction}; {@code accepted = query.qty − remainder.qty}.
 *   <li><b>Insert commit metadata:</b> a non-null metadata document is attached to the delivered stack
 *       via {@code new ItemStack(id, amount).withMetadata(metadata.clone())} (mirrors DropSink), so a
 *       damaged/enchanted/BlockHolder item delivered through a pipe round-trips byte-for-byte.
 *   <li><b>Insert simulate:</b> the engine's {@code addItemStack} has no dry-run that yields a partial
 *       count, and its partial-count test helpers are {@code protected}. So simulate is a read-only slot
 *       scan ({@code getCapacity}/{@code getItemStack}/{@code getMaxStack}): empty-slot capacity plus the
 *       room in same-id slots, capped at the requested amount. It never mutates. The commit reconciles
 *       any scan/engine divergence (the pure layer trusts the actual returned amount per the T2
 *       staleness contract).
 *       <p><b>NAMED DIVERGENCE</b> (documented residual, CRITICAL review fix): an occupied same-id slot
 *       counts as room ONLY when its metadata matches the inserting stack's ({@code Objects.equals} on
 *       the documents, both-null included) &mdash; mirroring the engine's {@code isStackableWith}
 *       (id AND durability AND metadata). The engine ALSO compares a durability field NOT exposed on the
 *       public {@code ItemStack} surface, so we cannot compare it: the residual is a CONSERVATIVE
 *       under-promise on durability-edge cases (a later re-route finds the real room: no churn). We never
 *       OVER-promise on metadata (which would cause launch-then-bounce): empty slots always count;
 *       same-id different-metadata slots never count.
 *   <li><b>Extract:</b> {@link #firstExtractable} scans slots for the first filter-admitted stack (capped);
 *       {@link #extract} commits via {@code removeItemStackFromSlot(slot, amount, false, false)} and reads
 *       the removed stack's metadata from {@code getOutput()}. Extract re-finds the slot by item id at
 *       commit time and tolerates a mid-tick race by extracting whatever is actually there.
 * </ul>
 *
 * <p>{@code filter=false} on add/remove: pipe transport is gated by OUR {@link ItemFilter} layer, not the
 * container's own slot filters (those govern player UI, not machine logistics). Defensive null-safety
 * throughout; never throws (the caller ticks on the world thread).
 */
public final class WorldContainerLookup implements ContainerLookup {

    private final World world;
    private final Store<ChunkStore> store;
    private final ComponentType<ChunkStore, ItemContainerBlock> containerType;

    public WorldContainerLookup(@Nonnull World world, @Nonnull Store<ChunkStore> store) {
        this.world = world;
        this.store = store;
        this.containerType = ItemContainerBlock.getComponentType();
    }

    @Override
    public ContainerView at(int x, int y, int z) {
        ItemContainer container = containerAt(x, y, z);
        return container == null ? null : new ItemContainerView(container);
    }

    /** Resolves the engine {@link ItemContainer} at a position, or null when there is no container block. */
    private ItemContainer containerAt(int x, int y, int z) {
        try {
            Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
            if (ref == null || !ref.isValid()) {
                return null;
            }
            ItemContainerBlock block = store.getComponent(ref, containerType);
            if (block == null) {
                return null;
            }
            return block.getItemContainer(); // @Nonnull: lazily allocated, never null once the component exists
        } catch (Throwable t) {
            return null; // never throw on the world thread
        }
    }

}
