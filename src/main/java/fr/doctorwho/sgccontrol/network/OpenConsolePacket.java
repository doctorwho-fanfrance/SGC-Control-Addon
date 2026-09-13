package fr.doctorwho.sgccontrol.network;

import fr.doctorwho.sgccontrol.client.SgcControlScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record OpenConsolePacket(BlockPos terminalPos) {
    public static void encode(OpenConsolePacket p, FriendlyByteBuf b) { b.writeBlockPos(p.terminalPos); }
    public static OpenConsolePacket decode(FriendlyByteBuf b) { return new OpenConsolePacket(b.readBlockPos()); }
    public static void handle(OpenConsolePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> Minecraft.getInstance().setScreen(new SgcControlScreen(p.terminalPos)));
        ctx.get().setPacketHandled(true);
    }
}
