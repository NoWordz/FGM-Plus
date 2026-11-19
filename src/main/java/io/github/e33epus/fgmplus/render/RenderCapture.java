package io.github.e33epus.fgmplus.render;

import org.joml.Matrix4f;

/**
 * Per-frame capture bridge between the transform window inside FGM's
 * {@code GenderLayer#setupTransformations} and the deferred breast geometry
 * commands ({@code BreastRenderCommand}) that draw later in the frame.
 *
 * <p>1.21.9+ renders entities through submit/collect pipelines: the vertex
 * submission no longer happens synchronously inside the layer, so the 1.20.1
 * approach of keeping the body view in static mixin fields breaks. Instead the
 * window captured while the layer transforms are set up is polled once per
 * geometry pass ({@link #pollWindow()}) and stored on the BreastModel instance
 * itself, where the deferred renderToBuffer finds it again.</p>
 *
 * <p>{@code layerIndex} numbers the geometry passes within one frame (0 = skin
 * breast, then jacket-wear, then armor/trim/glint); only index 0 clamps onto
 * the base torso-back plane, later layers clamp a hair outside it so stacked
 * back faces win the depth compare instead of z-fighting.</p>
 */
public final class RenderCapture {

	public record BodyWindow(Matrix4f view, Matrix4f inv, int layerIndex, float roundness, boolean cleavage) {
	}

	private static final ThreadLocal<Matrix4f> ACTIVE_VIEW = new ThreadLocal<>();
	private static final ThreadLocal<Matrix4f> ACTIVE_INV = new ThreadLocal<>();
	private static final ThreadLocal<Float> ACTIVE_ROUNDNESS = ThreadLocal.withInitial(() -> 0.0F);
	private static final ThreadLocal<Boolean> ACTIVE_CLEAVAGE = ThreadLocal.withInitial(() -> Boolean.TRUE);
	private static final ThreadLocal<Integer> LAYER_INDEX = ThreadLocal.withInitial(() -> 0);

	private RenderCapture() {
	}

	/**
	 * Opens a capture window around one breast side. {@code resetLayerIndex} is
	 * false for the armor layer, whose setup runs through the same (super) method
	 * but must continue the layer numbering instead of restarting it. {@code roundness}
	 * and {@code cleavage} ride along because the deferred render hook has no other
	 * way back to the per-player shape. Null matrices clear the window (degenerate
	 * pose -> vanilla path).
	 */
	public static void beginWindow(Matrix4f view, Matrix4f inv, boolean resetLayerIndex, float roundness, boolean cleavage) {
		if(view == null || inv == null) {
			ACTIVE_VIEW.remove();
			ACTIVE_INV.remove();
			ACTIVE_ROUNDNESS.remove();
			ACTIVE_CLEAVAGE.remove();
			return;
		}
		if(resetLayerIndex) {
			LAYER_INDEX.set(0);
		}
		ACTIVE_VIEW.set(view);
		ACTIVE_INV.set(inv);
		ACTIVE_ROUNDNESS.set(roundness);
		ACTIVE_CLEAVAGE.set(cleavage);
	}

	/** The window's body view matrix, or null outside a window. */
	public static Matrix4f activeView() {
		return ACTIVE_VIEW.get();
	}

	/**
	 * Called from the BreastModel constructor (once per geometry pass, in draw
	 * order); returns the window stamped with the current layer index and bumps
	 * that index, or null when constructed outside a window. 26.2's deferred
	 * submit/collect pipeline runs renderToBuffer long after the window closed,
	 * so the caller stores the result on the model instance itself.
	 */
	public static BodyWindow pollWindow() {
		Matrix4f view = ACTIVE_VIEW.get();
		Matrix4f inv = ACTIVE_INV.get();
		if(view == null || inv == null) {
			return null;
		}
		int index = LAYER_INDEX.get();
		LAYER_INDEX.set(index + 1);
		return new BodyWindow(view, inv, index, ACTIVE_ROUNDNESS.get(), ACTIVE_CLEAVAGE.get());
	}
}
