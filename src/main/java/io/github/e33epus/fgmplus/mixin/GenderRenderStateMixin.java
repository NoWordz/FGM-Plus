package io.github.e33epus.fgmplus.mixin;

import com.wildfire.client.render.GenderRenderState;
import com.wildfire.common.entities.EntityConfigHolder;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.shape.ShapeHolder;
import io.github.e33epus.fgmplus.shape.ShapeStateHolder;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bridges the per-player shape from the config holder onto FGM's per-frame
 * render state snapshot. On 26.2 the state is rebuilt by the renderer's state
 * extraction (GenderRenderState constructor, wired through the fabric
 * EntityRendererMixin) and receives the EntityConfigHolder directly, so the
 * constructor TAIL is the natural bridge point — and since a fresh state is
 * extracted whenever the game asks for one, live slider edits keep propagating.
 */
@Mixin(value = GenderRenderState.class, remap = false)
public abstract class GenderRenderStateMixin implements ShapeStateHolder {

	@Unique private volatile ShapeData fgmplus$shape;

	@Override
	public ShapeData fgmplus$getShape() {
		if(fgmplus$shape == null) {
			fgmplus$shape = new ShapeData();
		}
		return fgmplus$shape;
	}

	@Override
	public void fgmplus$setShape(ShapeData shape) {
		fgmplus$shape = shape == null ? new ShapeData() : shape;
	}

	@Inject(method = "<init>", at = @At("TAIL"), require = 1, remap = false)
	private void fgmplus$bridge(EntityConfigHolder<?> entityConfig, LivingEntity entity,
			HumanoidRenderState entityState, float partialTicks, CallbackInfo ci) {
		//only PlayerConfigHolder carries a shape (PlayerConfigMixin duck);
		//mannequin/armor-stand holders keep the per-state default
		if(entityConfig instanceof ShapeHolder holder) {
			fgmplus$setShape(holder.fgmplus$getShape());
		}
	}
}
