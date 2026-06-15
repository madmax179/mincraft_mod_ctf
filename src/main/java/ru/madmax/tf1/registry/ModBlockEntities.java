package ru.madmax.tf1.registry;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import ru.madmax.tf1.TF1Mod;
import ru.madmax.tf1.block.CapturePointBlockEntity;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TF1Mod.MOD_ID);

    public static final RegistryObject<BlockEntityType<CapturePointBlockEntity>> CAPTURE_POINT_BE =
            BLOCK_ENTITIES.register("capture_point",
                    () -> BlockEntityType.Builder
                            .of(CapturePointBlockEntity::new, ModBlocks.CAPTURE_POINT.get())
                            .build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
