package studio.antagon.modern.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import studio.antagon.modern.CapeBridge;

@Mixin(value = AbstractClientPlayer.class, priority = 900)
public abstract class CapeSkinMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void antagonCape(CallbackInfoReturnable<PlayerSkin> callback) {
        AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
        callback.setReturnValue(CapeBridge.skin(player.getUUID(), callback.getReturnValue()));
    }
}
