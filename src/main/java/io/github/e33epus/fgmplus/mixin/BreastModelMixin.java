package io.github.e33epus.fgmplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wildfire.client.render.BreastModel;
import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.render.RenderCapture;
import io.github.e33epus.fgmplus.render.RoundBreastMesh;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The custom vertex emitter for FGM 5 (26.2) breast geometry — two features in
 * the one hook where every pass crosses: the back-flatten clamp ported from
 * 1.20.1, and the Shape Studio roundness superellipsoid mesh
 * ({@link RoundBreastMesh}). With roundness at 0 the flatten path is
 * bit-identical to {@code WildfireModelRenderer#renderBox}.
 *
 * <p>On 26.2 all breast geometry (skin, jacket-wear, armor, trim) is emitted by
 * one {@link BreastModel}: the gender layer submits it directly and the armor
 * layer hands the same model to {@code EquipmentLayerRenderer#renderLayers}, so
 * overriding renderToBuffer covers every pass exactly like the retired
 * BreastRenderCommand hook did on 1.21.11.</p>
 *
 * <p>Every vertex whose body-space z lies beyond the torso-back plane is pulled
 * straight back onto it along the body-z axis; everything in front of the plane
 * keeps its transformed position. UVs, light, overlay and color pass through
 * untouched. The body view is captured per model instance at construction time
 * (see {@link RenderCapture}) because the submit/collect pipeline defers the
 * actual vertex work; models constructed while no capture window is active
 * render through the original renderBox path.</p>
 */
@Mixin(value = BreastModel.class, remap = false)
public abstract class BreastModelMixin {

	@Shadow private WildfireModelRenderer.ModelBox model;

	//outer layers (jacket-wear, armor) clamp a hair OUTSIDE the body plane so the
	//back faces of layers drawn later win the depth compare cleanly instead of
	//z-fighting with the breast body when everything is flattened onto one plane
	@Unique private static final float fp$CLAMP_BACK_Z = 2.125F * 0.0625F;
	@Unique private static final float fp$LAYER_EPSILON = 0.003F;

	@Unique private RenderCapture.BodyWindow fp$window;

	@Inject(method = "<init>", at = @At("TAIL"), require = 1, remap = false)
	private void fp$captureWindow(WildfireModelRenderer.ModelBox model, CallbackInfo ci) {
		this.fp$window = RenderCapture.pollWindow();
	}

	@Inject(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
			at = @At("HEAD"), cancellable = true, require = 1, remap = false)
	private void fp$flattenBack(PoseStack poseStack, VertexConsumer vertexConsumer, int light, int overlay, int color, CallbackInfo ci) {
		RenderCapture.BodyWindow window = this.fp$window;
		if(window == null || window.inv() == null) {
			return; //outside the capture window or degenerate pose -> original render
		}
		VertexConsumer buffer = vertexConsumer;
		Matrix4f pose = poseStack.last().pose();
		Matrix3f normalMat = poseStack.last().normal();
		float planeZ = window.layerIndex() == 0 ? fp$CLAMP_BACK_Z : fp$CLAMP_BACK_Z + fp$LAYER_EPSILON;
		//Constant per call: the view-space direction of the body-z axis (the pull
		//direction). Using the un-normalized linear part keeps everything affine
		//and scale-consistent: p' = p + (0,0,Δ) maps back to q' = q + R·(0,0,Δ)
		Vector3f pullDir = window.view().transformDirection(new Vector3f(0.0F, 0.0F, 1.0F));
		float roundness = window.roundness();
		if(roundness > 0.0F) {
			//Shape Studio roundness: emit the superellipsoid mesh instead of the flat
			//box quads. Same back-flatten clamp applies afterwards — from here on a
			//mesh vertex is just another local-space point like a box vertex.
			RoundBreastMesh mesh = RoundBreastMesh.of(this.model, roundness, window.cleavage());
			float[] d = mesh.data;
			for(int quad = 0; quad < mesh.quadCount; quad++) {
				for(int v = 0; v < 4; v++) {
					int o = quad * 32 + v * 8;
					Vector4f viewPos = new Vector4f(d[o], d[o + 1], d[o + 2], 1.0F).mul(pose);
					Vector3f body = window.inv().transformPosition(new Vector3f(viewPos.x(), viewPos.y(), viewPos.z()));
					if(body.z > planeZ) {
						float depth = body.z - planeZ;
						viewPos.x -= pullDir.x * depth;
						viewPos.y -= pullDir.y * depth;
						viewPos.z -= pullDir.z * depth;
					}
					Vector3f normal = new Vector3f(d[o + 3], d[o + 4], d[o + 5]).mul(normalMat);
					buffer.addVertex(viewPos.x(), viewPos.y(), viewPos.z(), color,
							d[o + 6], d[o + 7], overlay, light,
							normal.x(), normal.y(), normal.z());
				}
			}
			ci.cancel();
			return;
		}
		for(WildfireModelRenderer.TexturedQuad quad : this.model.quads) {
			//UVLayout can leave null slots for disabled faces; the mesh branch filters
			//them, so the flat branch must too (renderBox NPEs on null quads)
			if(quad == null || (quad.uvs[0] == 0.0F && quad.uvs[1] == 0.0F && quad.uvs[2] == 0.0F && quad.uvs[3] == 0.0F)) {
				continue;
			}
			Vector3f normal = new Vector3f(quad.normal.x(), quad.normal.y(), quad.normal.z()).mul(normalMat);
			for(WildfireModelRenderer.PositionTextureVertex vertex : quad.vertexPositions) {
				Vector4f viewPos = new Vector4f(vertex.x() / 16.0F, vertex.y() / 16.0F, vertex.z() / 16.0F, 1.0F).mul(pose);
				Vector3f body = window.inv().transformPosition(new Vector3f(viewPos.x(), viewPos.y(), viewPos.z()));
				if(body.z > planeZ) {
					float d = body.z - planeZ;
					viewPos.x -= pullDir.x * d;
					viewPos.y -= pullDir.y * d;
					viewPos.z -= pullDir.z * d;
				}
				buffer.addVertex(viewPos.x(), viewPos.y(), viewPos.z(), color,
						vertex.u(), vertex.v(), overlay, light,
						normal.x(), normal.y(), normal.z());
			}
		}
		ci.cancel();
	}
}
