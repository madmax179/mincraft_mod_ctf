package ru.madmax.tf1.registry;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import ru.madmax.tf1.TF1Mod;
import ru.madmax.tf1.block.CapturePointBlock;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TF1Mod.MOD_ID);

    public static final RegistryObject<Block> CAPTURE_POINT =
            BLOCKS.register("capture_point", CapturePointBlock::new);

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
