package io.github.e33epus.fgmplus.shape;

/**
 * Duck interface mixed into {@link com.wildfire.common.entities.players.PlayerConfigHolder}.
 */
public interface ShapeHolder {

	ShapeData fgmplus$getShape();

	void fgmplus$setShape(ShapeData shape);
}
