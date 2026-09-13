package fr.doctorwho.sgccontrol.network;

import fr.doctorwho.sgccontrol.jsg.JsgBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record GateActionPacket(BlockPos terminalPos, Action action, int value, String payload) {
    public enum Action { REFRESH, DIAL_TARGET, DIAL_MANUAL, ABORT, CLOSE, TOGGLE_IRIS }

    public static void encode(GateActionPacket p, FriendlyByteBuf b) {
        b.writeBlockPos(p.terminalPos);
        b.writeEnum(p.action);
        b.writeVarInt(p.value);
        b.writeUtf(p.payload == null ? "" : p.payload);
    }

    public static GateActionPacket decode(FriendlyByteBuf b) {
        return new GateActionPacket(b.readBlockPos(), b.readEnum(Action.class), b.readVarInt(), b.readUtf());
    }

    public static void handle(GateActionPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (player == null || player.distanceToSqr(
                    p.terminalPos.getX() + 0.5,
                    p.terminalPos.getY() + 0.5,
                    p.terminalPos.getZ() + 0.5) > 128 * 128) return;

            JsgBridge bridge = new JsgBridge(player.serverLevel(), p.terminalPos);
            switch (p.action) {
                case DIAL_TARGET -> bridge.dialTarget(p.payload, p.value);
                case DIAL_MANUAL -> bridge.dialManual(p.payload);
                case ABORT -> bridge.abort();
                case CLOSE -> bridge.close();
                case TOGGLE_IRIS -> bridge.toggleIris();
                case REFRESH -> { }
            }
            NetworkHandler.sendTo(player, bridge.snapshot());
        });
        ctx.get().setPacketHandled(true);
    }
}
