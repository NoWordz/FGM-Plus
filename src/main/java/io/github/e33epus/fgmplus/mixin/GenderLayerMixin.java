package io.github.e33epus.fgmplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wildfire.client.render.BreastSide;
import com.wildfire.client.render.GenderArmorLayer;
import com.wildfire.client.render.GenderLayer;
import com.wildfire.client.render.GenderRenderState;
import io.github.e33epus.fgmplus.render.DebugPlaneCommand;
import io.github.e33epus.fgmplus.render.RenderCapture;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.shape.ShapeStateHolder;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bust sizes above the vanilla slider cap (0.8) never grow the breast geometry:
 * the model box is a fixed 4x5x3 and the tilt rotation is clamped, so everything
 * past the cap is faked with positional shifts. This mixin freezes those
 * positional terms at the vanilla cap level and applies a real scale to
 * the breast model instead, layered with the per-player ShapeData (three-axis
 * scale + perkiness + position offsets). Back overflow is cured by the
 * per-vertex flatten in BreastModelMixin.
 *
 * <p>Ported to FGM 5 on MC 26.2: setupTransformations now receives the
 * {@link GenderRenderState} directly (no state-table lookup), and the body frame
 * is built by {@code model.root()} + {@code model.body} translateAndRotate calls —
 * replayed into a scratch stack here so the captured view matrix is bit-exact
 * for the geometry the same invocation later submits. GenderArmorLayer enters
 * through the same (super) method, so the zOffset freeze, the fingerprinted
 * translate redirects and the shape scale cover skin, jacket-wear and armor
 * passes alike.</p>
 */
@Mixin(value = GenderLayer.class, remap = false)
public abstract class GenderLayerMixin<STATE extends HumanoidRenderState, MODEL extends HumanoidModel<STATE>> {

	// Vanilla bust-size slider upper bound
	@Unique private static final float fp$VANILLA_BUST_CAP = 0.8F;
	// Replaces the retired bustScaleGain/bustScaleMax config keys (same values as their defaults)
	@Unique private static final float fp$SCALE_GAIN = 1.0F;
	@Unique private static final float fp$SCALE_MAX = 3.0F;
	// zOffset floor: FGM computes zOffset = 0.0625 - bustSize * 0.0625 (the positional
	// sink that deepens with bust size); freezing it at the vanilla cap level keeps
	// busts above 0.8 from being pushed further in
	@Unique private static final float fp$CAP_Z_OFFSET = (1.0F - fp$VANILLA_BUST_CAP) * 0.0625F;
	// The torso-back clamp plane itself lives in BreastModelMixin (the flatten
	// happens there); keeping a copy here would be a dead non-private @Unique field,
	// which the mixin validator rejects at apply time
	@Unique private static final float fp$DEG_TO_RAD = (float) (Math.PI / 180);

	@Shadow protected float breastSize;
	@Shadow protected float breastOffsetX;
	@Shadow protected float breastOffsetY;
	@Shadow protected float breastOffsetZ;
	@Shadow protected float zOffset;

	@Unique private ShapeData fp$shape;
	@Unique private float fp$bSize;

	@Unique private static float fp$realScale(float bSize) {
		if(bSize <= fp$VANILLA_BUST_CAP) {
			return 1.0F;
		}
		return Math.min(1.0F + (bSize - fp$VANILLA_BUST_CAP) * fp$SCALE_GAIN, fp$SCALE_MAX);
	}

	@Inject(method = "setupTransformations", at = @At("HEAD"), require = 1, remap = false)
	private void fp$capture(STATE state, MODEL model, GenderRenderState genderState, PoseStack matrixStack, BreastSide side, CallbackInfo ci) {
		fp$shape = null;
		fp$bSize = 0.0F;
		if(genderState != null) {
			if(genderState instanceof ShapeStateHolder holder) {
				fp$shape = holder.fgmplus$getShape();
			}
			//the physics-driven bust size the render chain itself uses (lerped)
			fp$bSize = genderState.leftBreastPhysics.getBreastSize();
		}
		//Freeze the depth sink: clamping the field BEFORE the method body runs keeps
		//the main positional translate (which reads zOffset) at the vanilla cap level
		this.zOffset = Math.max(this.zOffset, fp$CAP_Z_OFFSET);

		//Body view = entry pose (the stack is untouched at HEAD) + everything the
		//method applies before the per-breast transforms: the baby terms and the
		//root + body translateAndRotate calls. Replaying those calls through a
		//scratch PoseStack keeps the matrix bit-exact against future ModelPart
		//internals (rotation order, scale handling) instead of hand-guessing it.
		//The armor layer enters through the same (super) method with its own
		//entry pose and the same body transform, so the clamp plane is consistent
		//for skin, jacket-wear and armor geometry alike.
		try {
			PoseStack scratch = new PoseStack();
			//seed with the entry pose first: the final chain must read
			//entry * baby * root * body, exactly as the target method builds it
			scratch.last().pose().mul(matrixStack.last().pose());
			if(state.isBaby) {
				scratch.scale(state.ageScale, state.ageScale, state.ageScale);
				scratch.translate(0.0F, 0.75F, 0.0F);
			}
			model.root().translateAndRotate(scratch);
			ModelPart body = model.body;
			body.translateAndRotate(scratch);
			Matrix4f view = new Matrix4f(scratch.last().pose());
			Matrix4f inv = view.invert(new Matrix4f());
			float roundness = fp$shape != null ? fp$shape.getRoundness() : 0.0F;
			boolean cleavage = fp$shape == null || fp$shape.isCleavage();
			RenderCapture.beginWindow(view, inv, !((Object) this instanceof GenderArmorLayer), roundness, cleavage);
		} catch(Exception ignored) {
			//degenerate pose: clamp and panel fall back to vanilla rendering
			RenderCapture.beginWindow(null, null, false, 0.0F, true);
		}
	}

	/**
	 * All PoseStack#translate calls inside setupTransformations, demultiplexed by
	 * argument fingerprint. The two fingerprints are bit-exact products of the same
	 * shadowed fields the target method uses, so any upstream call-site reorder that
	 * changes the values degrades to vanilla passthrough instead of corrupting an
	 * unrelated translate.
	 */
	@Redirect(method = "setupTransformations", require = 1, remap = false,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", remap = true))
	private void fp$redirectTranslate(PoseStack stack, float x, float y, float z) {
		//the hang shift: translate(0, -0.035 * breastSize, 0); clamp the size term at
		//its vanilla cap level (breastSize = 0.8 * 1.5 + ... growth past 0.7)
		if(x == 0.0F && z == 0.0F && y == -0.035F * this.breastSize) {
			//26.2's setupRender maps the vanilla cap bust size (0.8) to
			//breastSize = 0.8 exactly (min(0.8*1.5, 0.7) + 0.5*|0.8-0.7|*2);
			//freezing the hang term there stops it sinking further past the cap
			stack.translate(x, -0.035F * Math.min(this.breastSize, fp$VANILLA_BUST_CAP), z);
			return;
		}
		//the main positional sink: (±offsetX*0.0625, 0.05625 + offsetY*0.0625,
		//zOffset - 0.125 + offsetZ*0.0425); add the body-space shape offsets here.
		//Offsets are applied before the tilt rotations and the later scale call, so
		//they are never magnified by the scale (front = -z, back = +z: a stored
		//negative offsetZ protrudes toward the chest front).
		float expectedX = this.breastOffsetX * 0.0625F;
		float expectedY = 0.05625F + this.breastOffsetY * 0.0625F;
		float expectedZ = this.zOffset - 0.0625F * 2.0F + this.breastOffsetZ * 0.0425F;
		if(Math.abs(z - expectedZ) < 1.0e-5F && Math.abs(y - expectedY) < 1.0e-5F
				&& (Math.abs(x - expectedX) < 1.0e-5F || Math.abs(x + expectedX) < 1.0e-5F)) {
			ShapeData shape = fp$shape != null ? fp$shape : new ShapeData();
			stack.translate(x + shape.getOffsetX(), y + shape.getOffsetY(), z + shape.getOffsetZ());
			return;
		}
		stack.translate(x, y, z);
	}

	/**
	 * Perkiness first (rotates about the same X axis as FGM's hardcoded -35 deg
	 * droop, positive values counteract the droop), then the real scale. Runs
	 * after FGM's own scale(0.9995, 1, 1) — the same spot the 1.20.1 port
	 * replaced FGM's scale call at — so the composite transform is unchanged.
	 */
	@Inject(method = "setupTransformations", at = @At("TAIL"), require = 1, remap = false)
	private void fp$applyShape(STATE state, MODEL model, GenderRenderState genderState, PoseStack matrixStack, BreastSide side, CallbackInfo ci) {
		ShapeData shape = fp$shape != null ? fp$shape : new ShapeData();
		float perk = shape.getPerkiness();
		if(perk != 0.0F) {
			matrixStack.mulPose(new Quaternionf().rotationXYZ(perk * fp$DEG_TO_RAD, 0.0F, 0.0F));
		}
		float s = fp$realScale(fp$bSize);
		//Each breast box already spans the full half-torso width, so the real-scale
		//growth (s) never applies to the X axis by default; the per-player scaleX
		//slider is the only way to widen (1.0 = vanilla width)
		matrixStack.scale(shape.getScaleX(), s * shape.getScaleY(), s * shape.getScaleZ());
	}

	/**
	 * Diagnostic aid: submits the torso-back plane through the same pipeline the
	 * breast geometry uses. Runs at TAIL of renderBreast, while the capture window
	 * of the left breast is still the active one.
	 */
	@Inject(method = "renderBreast", at = @At("TAIL"), require = 1, remap = false)
	private void fp$drawDebugPlane(STATE state, GenderRenderState genderState, PoseStack matrixStack,
			SubmitNodeCollector queue, int lightCoords, int overlay, BreastSide side, CallbackInfo ci) {
		if(side != BreastSide.LEFT || !io.github.e33epus.fgmplus.shape.ShapeRenderState.debugPlane) {
			return;
		}
		Matrix4f bodyView = RenderCapture.activeView();
		if(bodyView == null) {
			return;
		}
		DebugPlaneCommand.submit(queue, matrixStack, new Matrix4f(bodyView));
	}
}
