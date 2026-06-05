package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * H6 FIX 1 (buffer-share persistence) regression coverage, headless. Reproduces the in-game BUG 1:
 * a cell on a cable run fills the network; removing the cell invalidates+rebuilds the network and the
 * energy must NOT vanish. Survival works because:
 *
 * <ol>
 *   <li>each tick writes the network's {@code stored()} back into its member pipes' persisted
 *       {@link PipeNode#bufferShare()} (via {@link NetworkManager#splitEvenly}); and
 *   <li>{@link NetworkManager#getOrBuildNetwork} re-pools those persisted shares into the freshly
 *       built {@link Network} on the next access after invalidation.
 * </ol>
 *
 * <p>The write-back logic mirrors what {@code NetworkTickSystem} runs after each
 * {@link NetworkTransfer#distribute}; here we drive it directly against a fake grid so no live Hytale
 * world access is involved.
 */
class NetworkBufferPersistenceTest {

    /** A fake grid backed by a {@code Map<packedKey, PipeNode>}: a position with no entry has no pipe. */
    private static final class FakePipeGrid implements PipeGridView {
        private final Map<Long, PipeNode> pipes = new HashMap<>();

        FakePipeGrid put(int x, int y, int z, PipeNode node) {
            pipes.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        void remove(int x, int y, int z) {
            pipes.remove(NetworkManager.packKey(x, y, z));
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return pipes.get(NetworkManager.packKey(x, y, z));
        }
    }

    /**
     * Drives the SAME per-tick write-back the tick system runs ({@link NetworkManager#writeBackShares}):
     * split the network's stored total evenly across its members and persist each share (and the locked
     * resource id) onto the pipe. Calling the shared production method (not a copy) keeps this test
     * honest about the real logic.
     */
    private static void writeBack(Network net, FakePipeGrid grid) {
        NetworkManager.writeBackShares(net, grid);
    }

    private static PipeNode power(int tier) {
        return PipeNode.of(PortChannel.POWER, tier);
    }

    @Test
    void energySurvivesInvalidateAndRebuildAfterWriteBack() {
        // Three-pipe POWER run.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0))
            .put(2, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);
        // Energy arrives in the buffer (e.g. pulled from a cell over a tick).
        assertEquals(12, net.insert(null, 12, false));
        assertEquals(12, net.stored());

        // End-of-tick write-back persists the buffer into the pipe shares.
        writeBack(net, grid);
        // 12 over 3 members -> 4 each.
        assertEquals(4, grid.pipeAt(0, 0, 0).bufferShare());
        assertEquals(4, grid.pipeAt(1, 0, 0).bufferShare());
        assertEquals(4, grid.pipeAt(2, 0, 0).bufferShare());

        // Break event: invalidate one position (as PipeBreakEventSystem does). The cached, populated
        // Network object is dropped.
        mgr.invalidate(1, 0, 0);
        assertEquals(0, mgr.cachedNetworkCount());

        // Rebuild from a surviving pipe: pooling re-hydrates the buffer from the persisted shares.
        Network rebuilt = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertNotSame(net, rebuilt);
        assertEquals(12, rebuilt.stored(), "energy survived invalidate + rebuild");
    }

    @Test
    void rebuildWithoutWriteBackSincePoolingReflectsStaleShares() {
        // Documents the acceptable edge: if the buffer changed but no write-back ran since, a rebuild
        // pools the STALE (pre-insert) shares, not the live buffer. In-game this cannot lose committed
        // energy because place/break events fire BETWEEN ticks, after that tick's write-back persisted
        // the buffer. Here we deliberately skip write-back to show the pooled value is the stale 0.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0))
            .put(2, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);
        net.insert(null, 12, false); // buffer changed in memory only: no write-back
        assertEquals(12, net.stored());

        mgr.invalidate(1, 0, 0);
        Network rebuilt = mgr.getOrBuildNetwork(0, 0, 0, grid);
        // Shares are still their default 0, so the rebuilt buffer pools to 0. Acceptable per the design
        // note: events run between ticks, after the tick's write-back.
        assertEquals(0, rebuilt.stored());
    }

    @Test
    void poolingIsIdempotentAcrossRepeatedRebuilds() {
        // Pooling must not double-count: write-back keeps shares == buffer, so re-pooling the same
        // shares on every rebuild yields the same total (no growth).
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);
        net.insert(null, 8, false);
        writeBack(net, grid);

        mgr.invalidate(0, 0, 0);
        Network r1 = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(8, r1.stored());
        // Write-back again on the rebuilt network (shares unchanged: 4 + 4), then rebuild once more.
        writeBack(r1, grid);
        mgr.invalidate(0, 0, 0);
        Network r2 = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(8, r2.stored(), "pooling did not double-count across rebuilds");
    }

    @Test
    void writeBackClearsResourceIdForPower() {
        FakePipeGrid grid = new FakePipeGrid().put(0, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();
        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);
        net.insert(null, 5, false);
        writeBack(net, grid);
        assertNull(grid.pipeAt(0, 0, 0).resourceId(), "POWER never carries a resource id");
        assertEquals(5, grid.pipeAt(0, 0, 0).bufferShare());
    }
}
