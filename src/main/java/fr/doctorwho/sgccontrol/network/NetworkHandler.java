package fr.doctorwho.sgccontrol.network;

import fr.doctorwho.sgccontrol.SGCControlAddon;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {
    private static final String VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SGCControlAddon.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static int id;

    private NetworkHandler() {}

    public static void init() {
        CHANNEL.messageBuilder(OpenConsolePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenConsolePacket::encode).decoder(OpenConsolePacket::decode).consumerMainThread(OpenConsolePacket::handle).add();
        CHANNEL.messageBuilder(GateActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(GateActionPacket::encode).decoder(GateActionPacket::decode).consumerMainThread(GateActionPacket::handle).add();
        CHANNEL.messageBuilder(GateSnapshotPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(GateSnapshotPacket::encode).decoder(GateSnapshotPacket::decode).consumerMainThread(GateSnapshotPacket::handle).add();
    }

    public static void sendTo(ServerPlayer player, Object packet) {
        CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
