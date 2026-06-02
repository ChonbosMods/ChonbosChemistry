package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class EnergyBufferTest {

    private static <T> T decode(Codec<T> c, String json) throws Exception {
        return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void receiveClampsToCapacityAndReturnsAccepted() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        assertEquals(60, b.receiveEnergy(60, false));
        assertEquals(40, b.receiveEnergy(80, false));
        assertEquals(100, b.getStored());
    }

    @Test
    void receiveSimulateDoesNotMutate() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        assertEquals(100, b.receiveEnergy(500, true));
        assertEquals(0, b.getStored());
    }

    @Test
    void extractClampsToStoredAndReturnsProvided() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        b.receiveEnergy(50, false);
        assertEquals(50, b.extractEnergy(80, false));
        assertEquals(0, b.getStored());
    }

    @Test
    void codecRoundTripsStoredAndCapacity() throws Exception {
        EnergyBuffer b = decode(EnergyBuffer.CODEC, "{\"Stored\":30,\"Capacity\":100}");
        assertEquals(30, b.getStored());
        assertEquals(100, b.getMaxStored());
    }
}
