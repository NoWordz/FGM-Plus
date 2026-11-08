package io.github.e33epus.fgmplus.mixin;

import io.github.e33epus.fgmplus.FgmPlusClient;
import io.github.e33epus.fgmplus.shape.ShapeCache;
import io.github.e33epus.fgmplus.shape.ShapeData;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attaches the per-player shape (shared {@code ShapeCache} lookup by entity UUID)
 * onto every humanoid render state under {@link FgmPlusClient#STATE_KEY}, so the
 * render mixins can read it without touching any entity references. Same attach
 * point and mechanism as upstream FGM's own EntityRendererMixin (which carries
 * its GenderRenderState under FabricClientHelper.STATE) — render states are
 * recreated every frame, so the (cheap, cache-backed) lookup re-runs each frame
 * and studio edits become visible immediately.
 */
@Mixin(EntityRenderer.class)
public abstract class FgmPlusRenderStateMixin {

	@Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
			at = @At("TAIL"), require = 1)
	private void fgmplus$attachShape(Entity entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
		// Player-only gate: shapes are a per-player feature, so without this every
		// humanoid mob (zombies, villagers...) would grow the cache unbounded and
		// take a first-frame disk miss on the render thread. Same gating idea as
		// upstream's EntityRendererMixin (only tracked entities get state data).
		if(entity instanceof Player && cir.getReturnValue() instanceof HumanoidRenderState humanoidState) {
			ShapeData shape = ShapeCache.getOrCreate(entity.getUUID());
			humanoidState.setData(FgmPlusClient.STATE_KEY, shape);
		}
	}
}
