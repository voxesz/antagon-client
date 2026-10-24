package studio.antagon.inventory.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos") int antagonInventoryLeft();
    @Accessor("topPos") int antagonInventoryTop();
    @Accessor("imageWidth") int antagonInventoryWidth();
    @Accessor("imageHeight") int antagonInventoryHeight();
}
