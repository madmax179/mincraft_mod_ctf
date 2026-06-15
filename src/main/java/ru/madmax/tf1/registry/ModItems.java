package ru.madmax.tf1.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import ru.madmax.tf1.TF1Mod;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TF1Mod.MOD_ID);

    public static final RegistryObject<Item> CAPTURE_POINT_ITEM =
            ITEMS.register("capture_point",
                    () -> new BlockItem(ModBlocks.CAPTURE_POINT.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
