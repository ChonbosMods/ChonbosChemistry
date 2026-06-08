package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.ArrayList;
import java.util.List;

/**
 * The full set of configurable {@link Port}s on a machine, and the query "which ports
 * carry channel X in direction Y" that the transport layer asks each tick (design §5.3).
 */
public final class PortConfig {

    public static final BuilderCodec<PortConfig> CODEC = BuilderCodec.builder(PortConfig.class, PortConfig::new)
        .append(new KeyedCodec<>("Ports", new ArrayCodec<>(Port.CODEC, Port[]::new)),
            (o, v) -> o.ports = List.of(v), o -> o.ports.toArray(new Port[0])).add()
        .build();

    private List<Port> ports = List.of();

    private PortConfig() {
    }

    public static PortConfig of(List<Port> ports) {
        PortConfig c = new PortConfig();
        c.ports = List.copyOf(ports);
        return c;
    }

    /** @return the ports whose channel and direction both match. */
    public List<Port> portsFor(PortChannel channel, PortDirection direction) {
        List<Port> matches = new ArrayList<>();
        for (Port p : ports) {
            if (p.channel() == channel && p.direction() == direction) {
                matches.add(p);
            }
        }
        return matches;
    }

    /**
     * The single port on {@code faceIndex} carrying {@code channel}, or {@code null} if none. Under
     * one-port-per-face (design 2026-06-05 §1) a face carries at most one port per channel; this is the
     * face-precise lookup the endpoint collector uses to find the port that faces back at a pipe. If
     * several ports somehow match (legacy data), the first is returned.
     */
    public Port portAt(int faceIndex, PortChannel channel) {
        for (Port p : ports) {
            if (p.faceIndex() == faceIndex && p.channel() == channel) {
                return p;
            }
        }
        return null;
    }

    public List<Port> ports() {
        return ports;
    }

    /**
     * A new {@link PortConfig} with {@code port}'s face configured to exactly that port: every existing
     * port on the same {@code faceIndex} is dropped first (replace-not-append), so a face never ends up
     * carrying two ports under one-port-per-face (design 2026-06-05 §1). Used by the {@code CC_Wrench}
     * to persist a cycled machine face. {@code this} is left unchanged.
     *
     * @param port the new port for its face; a null port returns an unchanged copy.
     */
    public PortConfig withFacePort(Port port) {
        if (port == null) {
            return PortConfig.of(ports);
        }
        List<Port> rebuilt = new ArrayList<>(ports.size() + 1);
        for (Port p : ports) {
            if (p.faceIndex() != port.faceIndex()) {
                rebuilt.add(p);
            }
        }
        rebuilt.add(port);
        return PortConfig.of(rebuilt);
    }
}
