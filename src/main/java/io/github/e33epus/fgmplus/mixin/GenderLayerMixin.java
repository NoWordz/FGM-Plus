package io.github.e33epus.fgmplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wildfire.client.render.BreastSide;
import com.wildfire.client.render.GenderArmorLayer;
import com.wildfire.client.render.GenderLayer;
import com.wildfire.client.render.GenderRenderState;
import io.github.e33epus.fgmplus.FgmPlusClient;
import io.github.e33epus.fgmplus.render.DebugPlaneCommand;
import io.github.e33epus.fgmplus.render.RenderCapture;
import io.github.e33epus.fgmplus.shape.ShapeData;
import io.github.e33epus.fgmplus.shape.ShapeRenderState;
import net.minecraft.client.model.HumanoidModel;
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
 * per-vertex flatten in BreastModelMixin; the roundness superellipsoid mesh is
 * emitted there too.
 *
 * <p>The per-player shape rides on the render state under FgmPlusClient.STATE_KEY
 * (attached by FgmPlusRenderStateMixin); the 1.21.11 GenderRenderState duck
 * interface is gone because FGM 5.0.0 final reworked the state attach to a
 * Fabric RenderStateDataKey, and the state object itself no longer needs a mixin.</p>
 *
 * <p>All hooks sit on GenderLayer#setupTransformations / #renderBreast exactly as
 * in the 1.21.11 port; GenderArmorLayer still reaches them via super, so the
 * zOffset freeze, the fingerprinted translate redirects and the shape scale cover
 * skin, jacket-wear and armor passes alike.</p>
 */
@Mixin(value = GenderLayer.class, remap = false)
public abstract class GenderLayerMixin {

	// Vanilla ClientConfiguration.BUST_SIZE upper bound
	@Unique private static final float fp$VANILLA_BUST_CAP = 0.8F;
	// Replaces the retired bustScaleGain/bustScaleMax config keys (same values as their defaults)
	@Unique private static final float fp$SCALE_GAIN = 1.0F;
	@Unique private static final float fp$SCALE_MAX = 3.0F;
	// zOffset floor: FGM computes zOffset = 0.0625 - bustSize * 0.0625 (the positional
	// sink that deepens with bust size); freezing it at the vanilla cap level keeps
	// busts above 0.8 from being pushed further in
	@Unique private static final float fp$CAP_Z_OFFSET = (1.0F - fp$VANILLA_BUST_CAP) * 0.0625F;
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
	private void fp$capture(HumanoidRenderState state, HumanoidModel<?> model, GenderRenderState genderState,
			PoseStack matrixStack, BreastSide side, CallbackInfo ci) {
		//shape rides on the render state (FgmPlusRenderStateMixin attach); null = no
		//customize data yet, everything below falls back to vanilla behavior
		fp$shape = state == null ? null : state.getData(FgmPlusClient.STATE_KEY);
		fp$bSize = 0.0F;
		if(genderState != null) {
			//the physics-driven bust size the render chain itself uses (lerped)
			fp$bSize = genderState.leftBreastPhysics.getBreastSize();
		}
		//Freeze the depth sink: clamping the field BEFORE the method body runs keeps
		//the main positional translate (which reads zOffset) at the vanilla cap level
		this.zOffset = Math.max(this.zOffset, fp$CAP_Z_OFFSET);

		// Replay FGM's torso transforms on a copy of the entry pose. Using ModelPart's
		// own method preserves its rotation and per-axis scale in the clamp plane.
		try {
			PoseStack bodyPose = new PoseStack();
			bodyPose.last().set(matrixStack.last());
			if(state.isBaby) {
				bodyPose.scale(state.ageScale, state.ageScale, state.ageScale);
				bodyPose.translate(0.0F, 0.75F, 0.0F);
			}
			model.root().translateAndRotate(bodyPose);
			model.body.translateAndRotate(bodyPose);
			Matrix4f view = bodyPose.last().pose();
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
		//its vanilla cap level (breastSize = 0.8 * 2 - 0.7 = 0.9)
		if(x == 0.0F && z == 0.0F && y == -0.035F * this.breastSize) {
			float paramCap = fp$VANILLA_BUST_CAP * 2.0F - 0.7F;
			stack.translate(x, -0.035F * Math.min(this.breastSize, paramCap), z);
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
	private void fp$applyShape(HumanoidRenderState state, HumanoidModel<?> model, GenderRenderState genderState,
			PoseStack matrixStack, BreastSide side, CallbackInfo ci) {
		ShapeData shape = fp$shape != null ? fp$shape : new ShapeData();
		float perk = shape.getPerkiness();
		if(perk != 0.0F) {
			//26.3 renamed PoseStack#mulPose(Quaternionfc) to rotate(Quaternionfc) (same semantics)
			matrixStack.rotate(new Quaternionf().rotationXYZ(perk * fp$DEG_TO_RAD, 0.0F, 0.0F));
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
	 * of the left breast is still the active one. (Only the skin path reaches
	 * renderBreast — GenderArmorLayer overrides the render callback — which is
	 * exactly what the 1.21.11 port targeted as well.)
	 */
	@Inject(method = "renderBreast", at = @At("TAIL"), require = 1, remap = false)
	private void fp$drawDebugPlane(HumanoidRenderState state, GenderRenderState genderState, PoseStack matrixStack,
			SubmitNodeCollector queue, int lightCoords, int overlayCoords, BreastSide side, CallbackInfo ci) {
		if(side != BreastSide.LEFT || !ShapeRenderState.debugPlane) {
			return;
		}
		Matrix4f bodyView = RenderCapture.activeView();
		if(bodyView == null) {
			return;
		}
		DebugPlaneCommand.submit(queue, matrixStack, new Matrix4f(bodyView));
	}
}
