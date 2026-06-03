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
 *
 * <h2>Source/storage priority (H6 FIX 2)</h2>
 * Endpoints are split into PURE providers/acceptors (sources/sinks) and BUFFER providers/acceptors
 * (storage blocks with both ports). To avoid a storage block self-churning (re-supplying its own energy
 * each tick and starving the real source):
 * <ul>
 *   <li>Phase 1 pulls from PURE providers first; it pulls from buffer providers only when budget
 *       remains AND at least one pure acceptor exists (i.e. there is real demand to discharge into).
 *   <li>Phase 2 fair-splits to PURE acceptors first; any remaining budget then fair-splits to buffer
 *       acceptors (storage charges only the surplus the sinks did not take).
 * </ul>
 * Both phases keep the single per-tick throughput budget split across the priority tiers (the tiers
 * never sum above {@code throughput}), and every move is still simulate-then-commit min (lossless).
 */
public final class NetworkTransfer {

    private NetworkTransfer() {
    }

    /**
     * Convenience overload running one pass from the classified {@link NetworkEndpoints.Endpoints}.
     *
     * @return total units delivered to acceptors this call.
     */
    public static long distribute(Network net, NetworkEndpoints.Endpoints endpoints) {
        return distribute(
                net,
                endpoints.pureProviders(),
                endpoints.pureAcceptors(),
                endpoints.bufferProviders(),
                endpoints.bufferAcceptors());
    }

    /**
     * Back-compat overload: a pass with only pure providers/acceptors and no storage endpoints.
     *
     * @return total units delivered to acceptors this call.
     */
    public static long distribute(Network net, List<Provider> providers, List<Acceptor> acceptors) {
        return distribute(net, providers, acceptors, List.of(), List.of());
    }

    /**
     * Runs one provider -> buffer -> fair-split-acceptor pass with source/storage priority.
     *
     * @param pureProviders   sources (OUTPUT-only), pulled first.
     * @param pureAcceptors   sinks (INPUT-only), filled first.
     * @param bufferProviders storage provider views, pulled only when budget remains and demand exists.
     * @param bufferAcceptors storage acceptor views, charged only from the surplus budget.
     * @return total units delivered to acceptors this call.
     */
    public static long distribute(
            Network net,
            List<Provider> pureProviders,
            List<Acceptor> pureAcceptors,
            List<Provider> bufferProviders,
            List<Acceptor> bufferAcceptors) {
        boolean typeLocked =
                net.channel() == PortChannel.FLUID || net.channel() == PortChannel.GAS;

        // Per-call (per-tick) throughput budget. A 0-throughput network (empty membership) moves
        // nothing on either phase.
        long throughput = net.throughput();
        if (throughput <= 0) {
            return 0;
        }

        // Phase 1 — fill the network buffer from PURE providers first, capped at the throughput budget.
        long pulledBudget = throughput;
        pulledBudget = pullFrom(net, pureProviders, pulledBudget, typeLocked);
        // Then pull from BUFFER providers only when budget remains AND a pure acceptor (real demand)
        // exists; otherwise storage would churn its own energy and starve the real source.
        if (pulledBudget > 0 && !pureAcceptors.isEmpty()) {
            pulledBudget = pullFrom(net, bufferProviders, pulledBudget, typeLocked);
        }

        // Phase 2 — deliver out of the buffer: PURE acceptors first, then BUFFER acceptors from the
        // leftover of the SAME per-tick budget.
        long deliverBudget = Math.min(net.stored(), throughput);
        if (deliverBudget <= 0) {
            return 0;
        }
        long delivered = deliverTo(net, pureAcceptors, deliverBudget);
        long remainingBudget = deliverBudget - delivered;
        if (remainingBudget > 0) {
            // Re-clamp to what is still stored (deliveries to pure acceptors already drained the buffer).
            remainingBudget = Math.min(remainingBudget, net.stored());
            if (remainingBudget > 0) {
                delivered += deliverTo(net, bufferAcceptors, remainingBudget);
            }
        }
        return delivered;
    }

    /**
     * Pulls into the network buffer from {@code providers}, spending at most {@code budget} units.
     *
     * @return the budget remaining after this pull.
     */
    private static long pullFrom(Network net, List<Provider> providers, long budget, boolean typeLocked) {
        for (Provider provider : providers) {
            if (budget <= 0) {
                break; // spent this call's pull budget.
            }
            long freeSpace = net.capacity() - net.stored();
            if (freeSpace <= 0) {
                break; // network full — backpressure, stop pulling.
            }
            long room = Math.min(freeSpace, budget); // pull at most the remaining budget.
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
                budget -= moved;
            }
        }
        return budget;
    }

    /**
     * Fair-splits at most {@code budget} units out of the network buffer across {@code acceptors}.
     *
     * @return the total delivered (extracted from the net and accepted).
     */
    private static long deliverTo(Network net, List<Acceptor> acceptors, long budget) {
        if (budget <= 0 || acceptors.isEmpty() || net.stored() == 0) {
            return 0;
        }
        String r = net.lockedResourceId(); // null for POWER.
        long[] caps = new long[acceptors.size()];
        for (int i = 0; i < acceptors.size(); i++) {
            caps[i] = Math.max(0L, acceptors.get(i).capacityFor(r));
        }
        long[] alloc = FairSplitDistributor.allocate(budget, caps);

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
