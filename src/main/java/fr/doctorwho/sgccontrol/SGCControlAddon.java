package fr.doctorwho.sgccontrol;

import fr.doctorwho.sgccontrol.client.ClientEvents;
import fr.doctorwho.sgccontrol.network.NetworkHandler;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(SGCControlAddon.MODID)
public final class SGCControlAddon {
    public static final String MODID = "sgccontrol";
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Block> SGC_TERMINAL = BLOCKS.register("sgc_terminal",
            () -> new SgcTerminalBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.5f).requiresCorrectToolForDrops()));
    public static final RegistryObject<Item> SGC_TERMINAL_ITEM = ITEMS.register("sgc_terminal",
            () -> new BlockItem(SGC_TERMINAL.get(), new Item.Properties()));

    public SGCControlAddon() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener(this::creativeTab);
        NetworkHandler.init();
        ClientEvents.init(bus);
    }

    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) event.accept(SGC_TERMINAL_ITEM);
    }
}
