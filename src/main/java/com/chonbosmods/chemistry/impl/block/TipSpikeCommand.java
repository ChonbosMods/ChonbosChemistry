package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * TEMPORARY SPIKE PROBE: {@code /cc-tipspike}. DELETE AFTER THE OVERLAY-TIP SPIKE IS SIGNED OFF.
 *
 * <p>Proves the "overlay tip entity" recipe from
 * {@code docs/plans/2026-06-06-item-channel-design.md} (section "Spike findings (overlay tip
 * entities)") works in-game: a tiny static prop entity that renders an arbitrary blockymodel at
 * sub-block coords, is click-through (no {@code HitboxCollision}, no {@code Interactable}), and is
 * non-persistent ({@code NonSerialized}, gone after relog).
 *
 * <p>This class is glue and has NO unit tests. It is defensive: it never throws out of
 * {@link #execute}; any failure is reported to the player and logged.
 *
 * <h2>Usage</h2>
 * <ul>
 *   <li>{@code /cc-tipspike} : spawn one tip prop ~2 blocks in front of you at eye height.</li>
 *   <li>{@code /cc-tipspike <modelAssetId>} : spawn using a specific registered ModelAsset id.</li>
 *   <li>{@code /cc-tipspike clear} : remove every probe spawned this session.</li>
 * </ul>
 *
 * <h2>ModelAsset</h2>
 * The spike recipe requires a registered {@link ModelAsset} (the wire format needs an asset id, not
 * a raw path). Rather than declare a dedicated tip ModelAsset for the throwaway probe, this uses the
 * vanilla {@code Objective_Location_Marker} model by default (per the design doc's explicit FALLBACK:
 * "any small registered ModelAsset id works to prove the mechanism"). It is a small, visually
 * distinct marker prop. The dedicated tip ModelAsset ships with the real implementation.
 */
public final class TipSpikeCommand extends AbstractPlayerCommand {

    /** Vanilla marker model: small, distinct, definitely registered. Override via the command arg. */
    private static final String DEFAULT_MODEL_ASSET_ID = "Objective_Location_Marker";

    /** Probe scale: ~half a block, big enough to see, small enough to read as a "tip". */
    private static final float TIP_SCALE = 0.5f;

    /** How far in front of the player to drop the probe (blocks), and the eye-height lift. */
    private static final double FORWARD_OFFSET = 2.0;
    private static final double EYE_HEIGHT = 1.6;

    /**
     * Refs to every probe spawned this session, so {@code /cc-tipspike clear} can remove them.
     * Static + simple: this is a single-developer debug tool; not thread-hardened on purpose.
     */
    private static final List<Ref<EntityStore>> SPAWNED = new ArrayList<>();

    private final OptionalArg<String> argArg =
            withOptionalArg("arg", "ModelAsset id to spawn, or 'clear' to remove probes", ArgTypes.STRING);

    public TipSpikeCommand() {
        super("cc-tipspike", "TEMPORARY spike: spawn a static overlay-tip prop entity", false);
        addAliases("cctipspike");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {
        try {
            String arg = argArg.provided(context) ? argArg.get(context) : null;

            if (arg != null && "clear".equalsIgnoreCase(arg.trim())) {
                clearProbes(store, playerRef);
                return;
            }

            String modelId = (arg != null && !arg.trim().isEmpty())
                    ? arg.trim()
                    : DEFAULT_MODEL_ASSET_ID;

            spawnProbe(store, ref, playerRef, modelId);
        } catch (Throwable t) {
            // Never throw out of a command: report + log and move on.
            safeReply(playerRef, "cc-tipspike failed: " + t);
        }
    }

    private void spawnProbe(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerEntityRef,
            @Nonnull PlayerRef playerRef,
            @Nonnull String modelId) {
        ModelAsset tip = ModelAsset.getAssetMap().getAsset(modelId);
        if (tip == null) {
            safeReply(playerRef, "cc-tipspike: unknown ModelAsset id '" + modelId
                    + "'. Try a vanilla model id, or run /cc-tipspike with no argument for the default ("
                    + DEFAULT_MODEL_ASSET_ID + ").");
            return;
        }

        TransformComponent playerTransform =
                store.getComponent(playerEntityRef, TransformComponent.getComponentType());
        if (playerTransform == null) {
            safeReply(playerRef, "cc-tipspike: could not read your position (no TransformComponent).");
            return;
        }

        Vector3d playerPos = playerTransform.getPosition();
        Rotation3f playerRot = playerTransform.getRotation();

        // Derive the player's facing direction by rotating FORWARD (-Z) through their rotation, then
        // flatten to horizontal and step FORWARD_OFFSET blocks out, lifted to roughly eye height.
        Vector3d forward = playerRot.transform(new Vector3d(0.0, 0.0, -1.0));
        double horizLen = Math.hypot(forward.x, forward.z);
        double dirX = horizLen > 1.0e-6 ? forward.x / horizLen : 0.0;
        double dirZ = horizLen > 1.0e-6 ? forward.z / horizLen : -1.0;
        Vector3d spawnPos = new Vector3d(
                playerPos.x + dirX * FORWARD_OFFSET,
                playerPos.y + EYE_HEIGHT,
                playerPos.z + dirZ * FORWARD_OFFSET);

        // Face the tip back toward the player (look along the reverse of the step direction).
        Rotation3f tipRotation = Rotation3f.lookAt(new Vector3d(-dirX, 0.0, -dirZ));

        Model model = Model.createStaticScaledModel(tip, TIP_SCALE);

        // The exact spike recipe: render + network + non-persistent, NO hitbox / NO interactable.
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),
                new TransformComponent(spawnPos, tipRotation));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(PropComponent.getComponentType(), PropComponent.get());
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
        holder.addComponent(NetworkId.getComponentType(),
                new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        // DELIBERATELY OMITTED: HitboxCollision, Interactable, ItemComponent, ItemPhysicsComponent,
        //                       PersistentModel, PickupItemComponent, DespawnComponent.

        // Command runs on the WorldThread (AbstractPlayerCommand.runAsync(..., world)), so a direct
        // store.addEntity is safe and returns the Ref we keep for /cc-tipspike clear.
        Ref<EntityStore> spawned = store.addEntity(holder, AddReason.SPAWN);
        SPAWNED.add(spawned);

        safeReply(playerRef, "cc-tipspike: spawned '" + modelId + "' (scale " + TIP_SCALE
                + ") at " + fmt(spawnPos) + ". Total probes this session: " + SPAWNED.size() + ".");
        safeReply(playerRef, "check: renders? right size? click THROUGH it onto the block behind? "
                + "gone after relog? (/cc-tipspike clear to remove)");
    }

    private void clearProbes(@Nonnull Store<EntityStore> store, @Nonnull PlayerRef playerRef) {
        int removed = 0;
        for (Ref<EntityStore> probe : SPAWNED) {
            try {
                if (probe != null && probe.isValid()) {
                    store.removeEntity(probe, RemoveReason.REMOVE);
                    removed++;
                }
            } catch (Throwable t) {
                // Best-effort cleanup: skip any probe that resists removal.
            }
        }
        SPAWNED.clear();
        safeReply(playerRef, "cc-tipspike: removed " + removed + " probe(s).");
    }

    private static String fmt(@Nonnull Vector3d v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static void safeReply(@Nonnull PlayerRef playerRef, @Nonnull String text) {
        try {
            playerRef.sendMessage(Message.raw(text));
        } catch (Throwable ignored) {
            // Never let a reply failure escape.
        }
    }
}
