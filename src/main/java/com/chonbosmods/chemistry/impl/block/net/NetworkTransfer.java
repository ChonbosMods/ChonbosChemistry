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
 *
 * <h2>Storage balancing (Phase 3, 2026-06-05 design)</h2>
 * After the priority phases, storage endpoints drift toward {@link WaterFill} targets: capacity-blind
 * equal levels (a 5000-cap and a 1000-cap battery hold the same amount until the small one clamps
 * full, then the big one absorbs its share). Balancing spends only the LEFTOVER budget
 * ({@code min(unspent pull, unspent deliver)}), so real sources and sinks always outrank it, and it
 * is churn-free: targets are a pure function of total stored + capacities, movement needs a &ge;1
 * surplus and a &ge;1 deficit, and pulls are capped at the deliverable deficit so nothing strands in
 * the net buffer. See {@code docs/plans/2026-06-05-storage-balancing-design.md}.
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
                endpoints.bufferAcceptors(),
                endpoints.storages());
    }

    /**
     * Back-compat overload: a pass with only pure providers/acceptors and no storage endpoints.
     *
     * @return total units delivered to acceptors this call.
     */
    public static long distribute(Network net, List<Provider> providers, List<Acceptor> acceptors) {
        return distribute(net, providers, acceptors, List.of(), List.of(), List.of());
    }

    /**
     * Back-compat overload: storage endpoints as split provider/acceptor views only (no paired
     * gauges), so no balancing phase runs.
     *
     * @return total units delivered to acceptors this call.
     */
    public static long distribute(
            Network net,
            List<Provider> pureProviders,
            List<Acceptor> pureAcceptors,
            List<Provider> bufferProviders,
            List<Acceptor> bufferAcceptors) {
        return distribute(net, pureProviders, pureAcceptors, bufferProviders, bufferAcceptors, List.of());
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
    /**
     * Runs one provider -> buffer -> fair-split-acceptor pass with source/storage priority, then a
     * storage-balancing phase ({@link #balance}) on whatever per-tick budget is left over.
     *
     * @param storages paired storage views (gauges + provider/acceptor) for the balancing phase;
     *     typically the same underlying blocks as {@code bufferProviders}/{@code bufferAcceptors}.
     * @return total units delivered to acceptors this call (balancing deliveries included).
     */
    public static long distribute(
            Network net,
            List<Provider> pureProviders,
            List<Acceptor> pureAcceptors,
            List<Provider> bufferProviders,
            List<Acceptor> bufferAcceptors,
            List<StorageEndpoint> storages) {
        boolean typeLocked =
                net.channel() == PortChannel.FLUID || net.channel() == PortChannel.GAS;

        // Per-call (per-tick) throughput budget. A 0-throughput network (empty membership) moves
        // nothing on any phase.
        long throughput = net.throughput();
        if (throughput <= 0) {
            return 0;
        }

        // Phase 1: fill the network buffer from PURE providers first, capped at the throughput budget.
        long pulledBudget = throughput;
        pulledBudget = pullFrom(net, pureProviders, pulledBudget, typeLocked);
        // Then pull from BUFFER providers only when budget remains AND a pure acceptor (real demand)
        // exists; otherwise storage would churn its own energy and starve the real source.
        if (pulledBudget > 0 && !pureAcceptors.isEmpty()) {
            pulledBudget = pullFrom(net, bufferProviders, pulledBudget, typeLocked);
        }

        // Phase 2: deliver out of the buffer: PURE acceptors first, then BUFFER acceptors from the
        // leftover of the SAME per-tick budget.
        long delivered = 0;
        long deliverBudget = Math.min(net.stored(), throughput);
        if (deliverBudget > 0) {
            // Pass a fresh monotonic rotation offset per pass so the fair-split remainder recipient
            // cycles across ticks: equal acceptors with a stable order receive equally over time
            // instead of the first one permanently winning the leftover ±1 units.
            delivered = deliverTo(net, pureAcceptors, deliverBudget, net.nextRotation());
            long remainingBudget = deliverBudget - delivered;
            if (remainingBudget > 0) {
                // Re-clamp to what is still stored (deliveries to pure acceptors already drained the buffer).
                remainingBudget = Math.min(remainingBudget, net.stored());
                if (remainingBudget > 0) {
                    delivered += deliverTo(net, bufferAcceptors, remainingBudget, net.nextRotation());
                }
            }
        }

        // Phase 3 (balancing): storage drifts toward water-fill targets on LEFTOVER budget only:
        // bounded by both the unspent pull budget and the unspent deliver budget, so real sources and
        // sinks always outrank balancing and the per-phase throughput caps still hold.
        long balanceBudget = Math.min(pulledBudget, throughput - delivered);
        delivered += balance(net, storages, balanceBudget, typeLocked);
        return delivered;
    }

    /**
     * One storage-balancing step (2026-06-05 design): every storage drifts toward its
     * {@link WaterFill} target: capacity-blind equal levels, capacity only as a clamp. Donors
     * (above target) feed the net buffer; the pulled amount fair-splits across recipients (below
     * target) by deficit, so recipients fill at equal per-tick rates.
     *
     * <p>Churn-free by construction: targets are a pure function of total stored (unchanged by
     * internal moves) and capacities, movement requires a &ge;1 surplus AND a &ge;1 deficit, and the
     * pull is capped at the total deliverable deficit so nothing strands in the net buffer to be
     * redistributed off-target by the next tick's phase 2.
     *
     * @return units delivered into recipients this step.
     */
    private static long balance(Network net, List<StorageEndpoint> storages, long budget, boolean typeLocked) {
        int n = storages.size();
        if (budget <= 0 || n < 2) {
            return 0;
        }

        long[] caps = new long[n];
        long[] stored = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            caps[i] = Math.max(0L, storages.get(i).capacity());
            stored[i] = Math.min(Math.max(0L, storages.get(i).stored()), caps[i]);
            total += stored[i];
        }
        long[] targets = WaterFill.targets(caps, total);

        // The substance this balancing step will carry: the net's lock when set, else (type-locked
        // channels with an empty net) the first donor's held resource. null for POWER. Computing
        // deficits against the CARRIED substance matters: a recipient already holding water reports
        // capacityFor(null) == 0, which would stall balancing after the first step.
        String balanceId = net.lockedResourceId();
        if (typeLocked && balanceId == null) {
            for (int i = 0; i < n; i++) {
                if (stored[i] - targets[i] > 0) {
                    String r = storages.get(i).provider().resourceId();
                    if (r != null) {
                        balanceId = r;
                        break;
                    }
                }
            }
        }

        // Deliverable deficits (clamped by what each recipient can physically take right now).
        long[] deficits = new long[n];
        long totalDeficit = 0;
        for (int i = 0; i < n; i++) {
            long deficit = targets[i] - stored[i];
            if (deficit <= 0) {
                continue;
            }
            deficits[i] = Math.min(deficit, Math.max(0L, storages.get(i).acceptor().capacityFor(balanceId)));
            totalDeficit += deficits[i];
        }
        if (totalDeficit <= 0) {
            return 0; // everyone at/above target (floor remainder stays on donors): fixpoint.
        }

        // Pull from donors: capped by their surplus, the leftover budget, AND the total deliverable
        // deficit (pulling more would strand resource in the net buffer = next-tick churn).
        long pullCap = Math.min(budget, totalDeficit);
        long pulled = 0;
        for (int i = 0; i < n && pulled < pullCap; i++) {
            long surplus = stored[i] - targets[i];
            if (surplus <= 0) {
                continue;
            }
            long freeSpace = net.capacity() - net.stored();
            if (freeSpace <= 0) {
                break; // net buffer full: backpressure, stop pulling.
            }
            long want = Math.min(Math.min(surplus, pullCap - pulled), freeSpace);
            Provider provider = storages.get(i).provider();
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
            long offered = provider.extract(want, true);
            long accepted = net.insert(r, offered, true);
            long moved = Math.min(offered, accepted);
            if (moved > 0) {
                provider.extract(moved, false);
                net.insert(r, moved, false);
                pulled += moved;
            }
        }
        if (pulled <= 0) {
            return 0;
        }

        // Deliver exactly what was pulled, fair-split across recipients by deficit (re-read the lock:
        // the pull above may have just locked an empty fluid/gas net to the balanced substance).
        String r = net.lockedResourceId();
        long[] alloc = FairSplitDistributor.allocate(pulled, deficits, net.nextRotation());
        long delivered = 0;
        for (int i = 0; i < n; i++) {
            if (alloc[i] <= 0) {
                continue;
            }
            Acceptor acceptor = storages.get(i).acceptor();
            long accepted = acceptor.insert(r, alloc[i], true);
            long moved = Math.min(alloc[i], accepted);
            if (moved > 0) {
                // Extract from net only what was truly accepted, so a lying capacityFor cannot
                // make the network lose resource; a partial accept strands at most the difference
                // in the net buffer, which next tick's phase 2 hands back out.
                net.extract(moved, false);
                acceptor.insert(r, moved, false);
                delivered += moved;
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
                break; // network full : backpressure, stop pulling.
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
     * The {@code rotationOffset} cycles which acceptor receives the integer-division remainder so a
     * stable acceptor order does not permanently favour the first acceptor.
     *
     * @return the total delivered (extracted from the net and accepted).
     */
    private static long deliverTo(Network net, List<Acceptor> acceptors, long budget, int rotationOffset) {
        if (budget <= 0 || acceptors.isEmpty() || net.stored() == 0) {
            return 0;
        }
        String r = net.lockedResourceId(); // null for POWER.
        long[] caps = new long[acceptors.size()];
        for (int i = 0; i < acceptors.size(); i++) {
            caps[i] = Math.max(0L, acceptors.get(i).capacityFor(r));
        }
        long[] alloc = FairSplitDistributor.allocate(budget, caps, rotationOffset);

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
