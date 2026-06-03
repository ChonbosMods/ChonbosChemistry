package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.List;

/**
 * One transfer pass for a single {@link Network}: fill the shared buffer from providers, then
 * fair-split the buffer out to acceptors.
 *
 * <p>Every move is simulate-then-commit: the agreed amount is the {@code min} of what both sides
 * report on simulate, and exactly that amount is committed on both sides. This is lossless (a unit
 * never leaves one side without arriving at the other) and honors the network's FLUID/GAS type-lock.
 *
 * <p>Pure logic: JDK only, no Hytale APIs. Per-provider/per-acceptor rate limits live inside their
 * own {@code extract}/{@code insert}; this class handles the network-wide throughput cap.
 *
 * <p>NOTE (throughput cap): {@link Network#throughput()} is a PER-CALL (= per-tick) budget applied to
 * EACH phase independently. Phase 1 pulls at most {@code throughput} units from providers into the
 * buffer (also bounded by free space); Phase 2 delivers at most {@code throughput} units out of the
 * buffer to acceptors (fair-split of {@code min(stored, throughput)}). A network with
 * {@code throughput() == 0} (e.g. empty membership) moves nothing. This is what makes energy visibly
 * crawl through pipes: a full source no longer dumps to a sink in a single tick.
 */
public final class NetworkTransfer {

    private NetworkTransfer() {
    }

    /**
     * Runs one provider -> buffer -> fair-split-acceptor pass.
     *
     * @return total units delivered to acceptors this call.
     */
    public static long distribute(Network net, List<Provider> providers, List<Acceptor> acceptors) {
        boolean typeLocked =
                net.channel() == PortChannel.FLUID || net.channel() == PortChannel.GAS;

        // Per-call (per-tick) throughput budget. A 0-throughput network (empty membership) moves
        // nothing on either phase.
        long throughput = net.throughput();
        if (throughput <= 0) {
            return 0;
        }

        // Phase 1 — fill the network buffer from providers, capped at the throughput budget.
        long pulledBudget = throughput;
        for (Provider provider : providers) {
            if (pulledBudget <= 0) {
                break; // spent this call's pull budget.
            }
            long freeSpace = net.capacity() - net.stored();
            if (freeSpace <= 0) {
                break; // network full — backpressure, stop pulling.
            }
            long room = Math.min(freeSpace, pulledBudget); // pull at most the remaining budget.
            String r = provider.resourceId();
            if (typeLocked) {
                if (r == null) {
                    continue; // can't store null on a type-locked channel.
                }
                String locked = net.lockedResourceId();
                if (locked != null && !locked.equals(r)) {
                    continue; // type-lock: different resource, skip.
                }
            }
            long offered = provider.extract(room, true);
            long accepted = net.insert(r, offered, true);
            long moved = Math.min(offered, accepted);
            if (moved > 0) {
                provider.extract(moved, false);
                net.insert(r, moved, false);
                pulledBudget -= moved;
            }
        }

        // Phase 2 — fair-split the buffer to acceptors.
        if (net.stored() == 0 || acceptors.isEmpty()) {
            return 0;
        }
        String r = net.lockedResourceId(); // null for POWER.
        long[] caps = new long[acceptors.size()];
        for (int i = 0; i < acceptors.size(); i++) {
            caps[i] = Math.max(0L, acceptors.get(i).capacityFor(r));
        }
        // Deliver at most the throughput budget out of the buffer this call; the remainder stays
        // stored (visibly held in transit) for the next tick.
        long deliverBudget = Math.min(net.stored(), throughput);
        long[] alloc = FairSplitDistributor.allocate(deliverBudget, caps);

        long delivered = 0;
        for (int i = 0; i < acceptors.size(); i++) {
            if (alloc[i] <= 0) {
                continue;
            }
            Acceptor acceptor = acceptors.get(i);
            long accepted = acceptor.insert(r, alloc[i], true);
            long moved = Math.min(alloc[i], accepted);
            if (moved > 0) {
                // Extract from net only what was truly accepted, so a lying capacityFor cannot
                // make the network lose resource.
                net.extract(moved, false);
                acceptor.insert(r, moved, false);
                delivered += moved;
            }
        }
        return delivered;
    }
}
