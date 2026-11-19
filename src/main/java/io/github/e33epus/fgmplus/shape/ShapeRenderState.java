package io.github.e33epus.fgmplus.shape;

/**
 * Per-frame render-side toggles shared with the Shape Studio GUI.
 */
public final class ShapeRenderState {

	/**
	 * Diagnostic toggle (Shape Studio button): draws the computed torso-back
	 * plane as a translucent panel so its in-game fit can be verified before
	 * the back-flatten vertex clamp is trusted.
	 */
	public static volatile boolean debugPlane;

	private ShapeRenderState() {
	}
}
