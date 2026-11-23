package io.github.e33epus.fgmplus.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.e33epus.fgmplus.FgmPlusMod;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;

/**
 * Diagnostic aid: draws the torso-back plane exactly as FGM Plus computes it
 * (the same chain the back-flatten clamp uses), so its in-game fit can be
 * re-verified via the Shape Studio toggle. The command ignores the pose handed
 * over by the submit pipeline and lifts the panel through the captured body
 * view instead — the same matrix the flatten clamp measures against.
 */
public record DebugPlaneCommand(Matrix4f bodyView) implements SubmitNodeCollector.CustomGeometryRenderer {

	public static final float BACK_Z = 2.25F * 0.0625F;
	public static final float X_HALF = 4.0F * 0.0625F;
	public static final float Y_TOP = 0.0F;
	public static final float Y_BOTTOM = 12.0F * 0.0625F;
	// translucent green; both windings are submitted because debugQuads' cull
	// state is not contractual — the panel must be visible from either side
	private static final int COLOR = ARGB.color(90, 51, 255, 102);

	public static void submit(SubmitNodeCollector queue, com.mojang.blaze3d.vertex.PoseStack matrixStack, Matrix4f bodyView) {
		try {
			queue.submitCustomGeometry(matrixStack, RenderTypes.debugQuads(), new DebugPlaneCommand(bodyView));
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: failed to submit the debug torso-back plane", e);
		}
	}

	@Override
	public void render(PoseStack.Pose matricesEntry, VertexConsumer vertexConsumer) {
		quad(vertexConsumer, -X_HALF, Y_TOP, X_HALF, Y_BOTTOM);
		quad(vertexConsumer, X_HALF, Y_TOP, -X_HALF, Y_BOTTOM);
	}

	private void quad(VertexConsumer vc, float x0, float y0, float x1, float y1) {
		vertex(vc, x0, y0);
		vertex(vc, x1, y0);
		vertex(vc, x1, y1);
		vertex(vc, x0, y1);
	}

	private void vertex(VertexConsumer vc, float x, float y) {
		//debugQuads consumes position + color only; extra vertex elements are ignored
		vc.addVertex(bodyView, x, y, BACK_Z).setColor(COLOR);
	}
}
