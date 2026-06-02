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

    public List<Port> ports() {
        return ports;
    }
}
