package fr.doctorwho.sgccontrol.network;

import fr.doctorwho.sgccontrol.client.SgcControlScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record GateSnapshotPacket(boolean found,
                                 String state,
                                 String irisState,
                                 String irisType,
                                 String gateType,
                                 String localAddress,
                                 long energy,
                                 long maxEnergy,
                                 double ringAngle,
                                 boolean spinning,
                                 int dialedSymbols,
                                 List<String> dialedAddressSymbols,
                                 List<TargetInfo> targets,
                                 String message) {

    public record TargetInfo(String name,
                             String dimension,
                             String address,
                             String type,
                             int x,
                             int y,
                             int z,
                             boolean local,
                             boolean dialable,
                             int symbolsNeeded,
                             List<String> symbols) {
        public static void encode(TargetInfo t, FriendlyByteBuf b) {
            b.writeUtf(t.name);
            b.writeUtf(t.dimension);
            b.writeUtf(t.address);
            b.writeUtf(t.type);
            b.writeInt(t.x);
            b.writeInt(t.y);
            b.writeInt(t.z);
            b.writeBoolean(t.local);
            b.writeBoolean(t.dialable);
            b.writeVarInt(t.symbolsNeeded);
            b.writeVarInt(t.symbols.size());
            for (String symbol : t.symbols) b.writeUtf(symbol);
        }

        public static TargetInfo decode(FriendlyByteBuf b) {
            String name = b.readUtf();
            String dimension = b.readUtf();
            String address = b.readUtf();
            String type = b.readUtf();
            int x = b.readInt();
            int y = b.readInt();
            int z = b.readInt();
            boolean local = b.readBoolean();
            boolean dialable = b.readBoolean();
            int symbolsNeeded = b.readVarInt();
            int symbolCount = b.readVarInt();
            List<String> symbols = new ArrayList<>(symbolCount);
            for (int i = 0; i < symbolCount; i++) symbols.add(b.readUtf());
            return new TargetInfo(name, dimension, address, type, x, y, z,
                    local, dialable, symbolsNeeded, List.copyOf(symbols));
        }
    }

    public static void encode(GateSnapshotPacket p, FriendlyByteBuf b) {
        b.writeBoolean(p.found);
        b.writeUtf(p.state);
        b.writeUtf(p.irisState);
        b.writeUtf(p.irisType);
        b.writeUtf(p.gateType);
        b.writeUtf(p.localAddress);
        b.writeLong(p.energy);
        b.writeLong(p.maxEnergy);
        b.writeDouble(p.ringAngle);
        b.writeBoolean(p.spinning);
        b.writeVarInt(p.dialedSymbols);
        b.writeVarInt(p.dialedAddressSymbols.size());
        for (String symbol : p.dialedAddressSymbols) b.writeUtf(symbol);
        b.writeVarInt(p.targets.size());
        for (TargetInfo target : p.targets) TargetInfo.encode(target, b);
        b.writeUtf(p.message);
    }

    public static GateSnapshotPacket decode(FriendlyByteBuf b) {
        boolean found = b.readBoolean();
        String state = b.readUtf();
        String iris = b.readUtf();
        String irisType = b.readUtf();
        String gateType = b.readUtf();
        String localAddress = b.readUtf();
        long energy = b.readLong();
        long max = b.readLong();
        double angle = b.readDouble();
        boolean spinning = b.readBoolean();
        int dialed = b.readVarInt();
        int dialedCount = b.readVarInt();
        List<String> dialedAddressSymbols = new ArrayList<>(dialedCount);
        for (int i = 0; i < dialedCount; i++) dialedAddressSymbols.add(b.readUtf());
        int count = b.readVarInt();
        List<TargetInfo> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) targets.add(TargetInfo.decode(b));
        String msg = b.readUtf();
        return new GateSnapshotPacket(found, state, iris, irisType, gateType, localAddress,
                energy, max, angle, spinning, dialed, List.copyOf(dialedAddressSymbols), targets, msg);
    }

    public static void handle(GateSnapshotPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof SgcControlScreen screen) screen.acceptSnapshot(p);
        });
        ctx.get().setPacketHandled(true);
    }
}
