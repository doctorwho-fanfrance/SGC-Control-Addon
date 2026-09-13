package fr.doctorwho.sgccontrol.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;

public final class ClientEvents {
    private ClientEvents() {}
    public static void init(IEventBus ignored) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {});
    }
}
