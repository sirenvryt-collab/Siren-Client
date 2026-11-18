package dev.sirenclient.mixin;

import dev.sirenclient.FakeHud;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla pulls the third-person camera in front of any block behind you (getMaxZoom).
 * With the toggle on we return the full requested distance, so a free-look camera passes through walls.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true, require = 0)
    private void sirenNoClip(float maxZoom, CallbackInfoReturnable<Float> cir) {
        if (FakeHud.cameraNoClip) cir.setReturnValue(maxZoom);
    }
}
