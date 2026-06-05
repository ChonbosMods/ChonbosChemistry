package com.chonbosmods.chemistry.impl.block.net;

/**
 * A storage block (BOTH-port endpoint: battery, tank) as the balancing phase sees it: the paired
 * provider/acceptor views {@link NetworkEndpoints} already builds, plus the live stored/capacity
 * gauges {@link WaterFill} targets need. The narrow {@link Provider}/{@link Acceptor} seams stay
 * gauge-free on purpose; only balancing needs to know how full a storage is.
 */
public interface StorageEndpoint {

    /** Currently stored amount (live, not a snapshot). */
    long stored();

    /** Total capacity (not free space). */
    long capacity();

    /** This storage's provider view (the same instance the buffer-provider list carries). */
    Provider provider();

    /** This storage's acceptor view (the same instance the buffer-acceptor list carries). */
    Acceptor acceptor();
}
