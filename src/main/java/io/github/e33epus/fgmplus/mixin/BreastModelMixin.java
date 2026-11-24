package io.github.e33epus.fgmplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wildfire.client.render.BreastModel;
import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import io.github.e33epus.fgmplus.render.RenderCapture;
import io.github.e33epus.fgmplus.render.RoundBreastMesh;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The custom vertex emitter for FGM 5.0.0's breast geometry — two features in the
 * one hook where every pass (skin, jacket-wear, armor, trim, glint) crosses:
 * the back-flatten clamp ported from 1.20.1, and the Shape Studio roundness
 * superellipsoid mesh ({@link RoundBreastMesh}). With roundness at 0 the
 * flatten path is bit-identical to the original render.
 *
 * <p>5.0.0 routes ALL breast geometry (skin, jacket-wear, armor) through
 * {@code BreastModel} submissions, and every transform chain involved runs
 * through the common GenderLayer#setupTransformations body frame (GenderArmorLayer
 * via super), so one hook here covers all passes. The body view is captured per
 * model instance at construction time (see RenderCapture) because the
 * submit/collect pipeline defers the actual vertex work.</p>
 *
 * <p>Shader-pack note: the emission must stay on the chained {@code addVertex}
 * path. Iris recomputes per-quad normals there, but its gbuffer pipeline only
 * consumes vertices that entered the builder through that path — a Sodium
 * {@code VertexBufferWriter.push} emission (verified in-game, builds 055c20b2..334214ec)
 * registers in the builder yet never reaches the GPU. The roundness mesh instead
 * keeps its texture mapping sane via {@code meshTexelCenterUv} (see RoundBreastMesh).</p>
 */
@Mixin(value = BreastModel.class, remap = false)
public abstract class BreastModelMixin {

	@Shadow @Final private WildfireModelRenderer.ModelBox model;

	//outer layers (jacket-wear, armor) clamp a hair OUTSIDE the body plane so the
	//back faces of layers drawn later win the depth compare cleanly instead of
	//z-fighting with the breast body when everything is flattened onto one plane
	@Unique private static final float fp$CLAMP_BACK_Z = 2.125F * 0.0625F;
	@Unique private static final float fp$LAYER_EPSILON = 0.003F;

	//render-thread-only scratch reused across mesh emission calls (single model per call)
	@Unique private final Vector4f[] fp$quadPositions = new Vector4f[4];
	@Unique private final float[] fp$quadBodyZ = new float[4];
	@Unique private static long fp$lastPullLogMs;

	//Iris shadow-pass probe via reflection: Iris is optional at runtime (no Iris = no
	//shader pack = no bug), and shading the API as an implement dep would hard-wire it
	@Unique private static Object fp$irisApi;
	@Unique private static java.lang.reflect.Method fp$irisShadowMethod;
	@Unique private static boolean fp$irisAbsent;

	@Unique private static boolean fp$isShadowPass() {
		if(fp$irisAbsent) {
			return false;
		}
		try {
			if(fp$irisShadowMethod == null) {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				fp$irisApi = api.getMethod("getInstance").invoke(null);
				fp$irisShadowMethod = api.getMethod("isRenderingShadowPass");
			}
			return (Boolean) fp$irisShadowMethod.invoke(fp$irisApi);
		} catch(ClassNotFoundException | NoSuchMethodException e) {
			//Iris absent (or API drift): no shadow pass to care about
			fp$irisAbsent = true;
			fp$irisShadowMethod = null;
			fp$irisApi = null;
			return false;
		} catch(Throwable t) {
			return false;
		}
	}

	/**
	 * Throttled observability for the pull stats behind {@code meshDebugLog}.
	 */
	@Unique private static void fp$pullStats(int totalQuads, int pulledQuads, float maxDepth, float roundness, int layerIndex) {
		if(!FgmPlusConfig.meshDebugLog || pulledQuads == 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if(now - fp$lastPullLogMs < 5000L) {
			return;
		}
		fp$lastPullLogMs = now;
		FgmPlusMod.LOGGER.info("FGM Plus [pull] quads={}/{} pulled={} maxDepth={}px roundness={} layer={} mode={}",
				pulledQuads, totalQuads, pulledQuads, String.format("%.3f", maxDepth * 16.0F),
				roundness, layerIndex, FgmPlusConfig.meshPullMode);
	}

	@Inject(method = "<init>(Lcom/wildfire/client/render/WildfireModelRenderer$ModelBox;)V",
			at = @At("TAIL"), require = 1, remap = false)
	private void fgmplus$captureWindow(WildfireModelRenderer.ModelBox box, CallbackInfo ci) {
		RenderCapture.registerCommand(this);
	}

	@Inject(method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
			at = @At("HEAD"), cancellable = true, require = 1, remap = false)
	private void fgmplus$flattenBack(PoseStack poseStack, VertexConsumer vertexConsumer, int light, int overlay,
			int color, CallbackInfo ci) {
		RenderCapture.BodyWindow window = RenderCapture.forCommand(this);
		if(window == null || window.inv() == null) {
			return; //outside the capture window or degenerate pose -> original render
		}
		PoseStack.Pose entry = poseStack.last();
		Matrix4f pose = entry.pose();
		Matrix3f normalMat = entry.normal();
		float planeZ = window.layerIndex() == 0 ? fp$CLAMP_BACK_Z : fp$CLAMP_BACK_Z + fp$LAYER_EPSILON;
		//Constant per call: the view-space direction of the body-z axis (the pull
		//direction). Using the un-normalized linear part keeps everything affine
		//and scale-consistent: p' = p + (0,0,Δ) maps back to q' = q + R·(0,0,Δ)
		Vector3f pullDir = window.view().transformDirection(new Vector3f(0.0F, 0.0F, 1.0F));
		float roundness = window.roundness();
		//In Iris's shadow pass the roundness mesh degrades to the flat box: shader packs
		//tune their shadow-map normal bias for planar depth gradients, and a curved
		//entity surface self-shadows with acne no bias compensates. The shadow silhouette
		//barely changes; the lit chest stops eating itself.
		boolean flatForShadow = FgmPlusConfig.meshShadowFlat && fp$isShadowPass();
		if(roundness > 0.0F && !flatForShadow) {
			//Shape Studio roundness: emit the superellipsoid mesh instead of the flat
			//box quads. The back-flatten clamp applies per quad by default (meshPullMode):
			//Iris recomputes every entity quad's face normal from its vertex positions,
			//so a per-vertex pull stretches curved quads and the recomputed normals
			//rotate away from the surface. "per_vertex" restores the legacy behavior;
			//"off" disables the pull entirely (diagnosis only).
			RoundBreastMesh mesh = RoundBreastMesh.of(this.model, roundness,
					window.cleavage() && !FgmPlusConfig.meshDebugNoBridge);
			//debug-loop: pick up config file edits without a game restart (1s throttle)
			FgmPlusConfig.maybeReload();
			float[] d = mesh.data;
			//diagnostic count sweep: -1 = all quads
			int quadLimit = FgmPlusConfig.meshDebugMaxQuads < 0 ? mesh.quadCount
					: Math.min(mesh.quadCount, FgmPlusConfig.meshDebugMaxQuads);
			String pullMode = FgmPlusConfig.meshPullMode;
			boolean rigidPull = !"per_vertex".equals(pullMode);
			boolean disablePull = "off".equals(pullMode);
			int pulledQuads = 0;
			float maxPullDepth = 0.0F;
			Vector4f[] quadPos = fp$quadPositions;
			float[] quadBodyZ = fp$quadBodyZ;
			for(int quad = 0; quad < quadLimit; quad++) {
				float deepestBodyZ = Float.NEGATIVE_INFINITY;
				for(int v = 0; v < 4; v++) {
					int o = quad * 32 + v * 8;
					Vector4f viewPos = quadPos[v] == null ? quadPos[v] = new Vector4f() : quadPos[v];
					viewPos.set(d[o], d[o + 1], d[o + 2], 1.0F).mul(pose);
					float bodyZ = window.inv().transformPosition(
							new Vector3f(viewPos.x(), viewPos.y(), viewPos.z())).z();
					quadBodyZ[v] = bodyZ;
					if(bodyZ > deepestBodyZ) {
						deepestBodyZ = bodyZ;
					}
				}
				float quadDepth = disablePull ? 0.0F : Math.max(deepestBodyZ - planeZ, 0.0F);
				if(quadDepth > 0.0F) {
					pulledQuads++;
					if(quadDepth > maxPullDepth) {
						maxPullDepth = quadDepth;
					}
				}
				for(int v = 0; v < 4; v++) {
					int o = quad * 32 + v * 8;
					Vector4f viewPos = quadPos[v];
					float depth = rigidPull ? quadDepth : Math.max(quadBodyZ[v] - planeZ, 0.0F);
					if(depth > 0.0F) {
						viewPos.x -= pullDir.x * depth;
						viewPos.y -= pullDir.y * depth;
						viewPos.z -= pullDir.z * depth;
					}
					Vector3f normal = new Vector3f(d[o + 3], d[o + 4], d[o + 5]).mul(normalMat);
					vertexConsumer.addVertex(viewPos.x(), viewPos.y(), viewPos.z(), color,
							d[o + 6], d[o + 7], overlay, light,
							normal.x(), normal.y(), normal.z());
				}
			}
			fp$pullStats(quadLimit, pulledQuads, maxPullDepth, roundness, window.layerIndex());
			ci.cancel();
			return;
		}
		for(WildfireModelRenderer.TexturedQuad quad : this.model.quads) {
			//UVLayout can leave null slots for disabled faces; the mesh branch filters
			//them, so the flat branch must too (upstream renderBox would NPE here)
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
				vertexConsumer.addVertex(viewPos.x(), viewPos.y(), viewPos.z(), color,
						vertex.u(), vertex.v(), overlay, light,
						normal.x(), normal.y(), normal.z());
			}
		}
		ci.cancel();
	}
}
