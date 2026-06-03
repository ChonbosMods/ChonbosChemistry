package com.chonbosmods.chemistry.impl.block.net;

/**
 * Test-seam for something that receives resource out of a {@link Network}. Per-acceptor rate limits
 * are encapsulated here: {@link #capacityFor}/{@link #insert} already cap at the acceptor's own
 * throughput.
 */
public interface Acceptor {

    /** Room this acceptor can take of the given resource right now (0 if it cannot accept it). */
    long capacityFor(String resourceId);

    /**
     * Insert up to {@code amount} of {@code resourceId}.
     *
     * @return amount actually accepted; {@code simulate == true} means no mutation.
     */
    long insert(String resourceId, long amount, boolean simulate);
}
