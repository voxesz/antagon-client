package studio.antagon.modern.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.antagon.modern.AntagonModern;

@Mixin(KeyboardInput.class)
public abstract class SprintInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void antagonSprint(CallbackInfo callback) {
        keyPresses = AntagonModern.sprintInput(keyPresses);
    }
}
