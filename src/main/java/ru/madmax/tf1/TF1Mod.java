package ru.madmax.tf1;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ru.madmax.tf1.command.TF1Command;
import ru.madmax.tf1.event.TF1EventHandlers;
import ru.madmax.tf1.registry.ModBlockEntities;
import ru.madmax.tf1.registry.ModBlocks;
import ru.madmax.tf1.registry.ModItems;

@Mod(TF1Mod.MOD_ID)
public class TF1Mod {

    public static final String MOD_ID = "tf1";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TF1Mod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new TF1EventHandlers());

        LOGGER.info("TF1 mod loaded.");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TF1Command.register(event.getDispatcher());
    }
}
