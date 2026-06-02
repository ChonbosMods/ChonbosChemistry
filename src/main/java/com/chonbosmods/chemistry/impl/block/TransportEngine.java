package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;

/**
 * The push-based, lossless transport algorithm: each tick a source node offers what it can out of
 * each OUTPUT port to the adjacent node, capped by throughput and the receiver's free space.
 *
 * <p>Transfer is <b>simulate-then-commit</b>: the movable amount is computed with {@code simulate=true}
 * on both sides, then the agreed {@code min} is committed with {@code simulate=false} on both sides.
 * This is lossless and never voids resources or energy.
 *
 * <p>A source OUTPUT port's {@link Port#faceIndex()} IS the direction looked up via the
 * {@link NeighborView}; aligning these indices to Hytale face indices is a later (Phase B) task.
 *
 * <p><b>Simplification:</b> an output port transfers to a neighbor if that neighbor has ANY INPUT
 * port of the same channel. Precise opposite-face geometry (a neighbor's INPUT must face back at the
 * source) is deferred to the Phase B wiring task.
 *
 * <p>Lives in {@code impl.block} (not {@code api.io}) so the api stays contracts-only. Promotion to
 * api is deferred until third-party blocks need to participate in transport.
 */
public final class TransportEngine {

    private static final PortChannel[] RESOURCE_CHANNELS = {
        PortChannel.ITEM, PortChannel.FLUID, PortChannel.GAS
    };

    private TransportEngine() {
    }

    /** Pushes energy out of every POWER OUTPUT port of {@code source} into adjacent power receivers. */
    public static void pushEnergy(TransferNode source, NeighborView view) {
        EnergyHandler src = source.energy();
        if (src == null) {
            return;
        }
        for (Port p : source.ports().portsFor(PortChannel.POWER, PortDirection.OUTPUT)) {
            TransferNode nb = view.neighborIn(p.faceIndex());
            if (nb == null) {
                continue;
            }
            if (nb.ports().portsFor(PortChannel.POWER, PortDirection.INPUT).isEmpty()) {
                continue;
            }
            EnergyHandler dst = nb.energy();
            if (dst == null) {
                continue;
            }
            int cap = source.throughput(PortChannel.POWER);
            int offered = src.extractEnergy(cap, true);
            int accepted = dst.receiveEnergy(offered, true);
            if (accepted > 0) {
                src.extractEnergy(accepted, false);
                dst.receiveEnergy(accepted, false);
            }
        }
    }

    /** Pushes items/fluids/gases out of every matching OUTPUT port of {@code source} into adjacent receivers. */
    public static void pushResources(TransferNode source, NeighborView view) {
        for (PortChannel channel : RESOURCE_CHANNELS) {
            ResourceBuffer srcBuf = source.resource(channel);
            if (srcBuf == null || srcBuf.resourceId() == null) {
                continue;
            }
            for (Port p : source.ports().portsFor(channel, PortDirection.OUTPUT)) {
                TransferNode nb = view.neighborIn(p.faceIndex());
                if (nb == null) {
                    continue;
                }
                if (nb.ports().portsFor(channel, PortDirection.INPUT).isEmpty()) {
                    continue;
                }
                ResourceBuffer dstBuf = nb.resource(channel);
                if (dstBuf == null) {
                    continue;
                }
                String id = srcBuf.resourceId(); // re-read: may become null after a full drain
                if (id == null) {
                    break;
                }
                int cap = source.throughput(channel);
                int offered = srcBuf.extract(cap, true);
                int accepted = dstBuf.insert(id, offered, true);
                if (accepted > 0) {
                    srcBuf.extract(accepted, false);
                    dstBuf.insert(id, accepted, false);
                }
            }
        }
    }
}
