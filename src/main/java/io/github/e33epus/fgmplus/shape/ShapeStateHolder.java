package io.github.e33epus.fgmplus.shape;

/**
 * Duck interface mixed into {@link com.wildfire.render.GenderRenderState}, bridging the
 * per-player shape from the config object onto the per-frame render state snapshot.
 */
public interface ShapeStateHolder {

	ShapeData fgmplus$getShape();

	void fgmplus$setShape(ShapeData shape);
}
