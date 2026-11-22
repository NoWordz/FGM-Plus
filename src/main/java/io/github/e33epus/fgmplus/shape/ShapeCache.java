package io.github.e33epus.fgmplus.shape;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory per-player shape cache, the 26.1 successor of the duck-interface
 * approach (FGM 5.0.0 deleted {@code PlayerConfig}, so there is nothing left to
 * mix a {@code ShapeHolder} into). Every consumer — the render-state attach
 * mixin, the Shape Studio sliders, the sync payload handlers — reads and writes
 * the same live {@link ShapeData} instance per UUID.
 *
 * <p>Disk fallback: a cache miss loads config/fgmplus/shapes/&lt;UUID&gt;.json
 * once (ShapeStore) and caches the result, so remote players' saved shapes
 * survive relogs exactly like the old loadFromDisk hook did, without repeated
 * IO (render states are recreated every frame and re-query this cache).</p>
 */
public final class ShapeCache {

	private static final Map<UUID, ShapeData> SHAPES = new ConcurrentHashMap<>();

	private ShapeCache() {
	}

	/**
	 * The live shape instance for {@code uuid}; edits made on it are visible to
	 * the renderer on the next frame and to every later sync send.
	 */
	public static ShapeData getOrCreate(UUID uuid) {
		return SHAPES.computeIfAbsent(uuid, ShapeStore::load);
	}

	/** Replaces the cached shape; null removes the entry. Stores a copy. */
	public static void put(UUID uuid, @Nullable ShapeData shape) {
		if(shape == null) {
			SHAPES.remove(uuid);
		} else {
			SHAPES.put(uuid, shape.copy());
		}
	}

	/** Writes the cached shape (if any) to disk; default shapes delete the file. */
	public static void persist(UUID uuid) {
		ShapeStore.save(uuid, SHAPES.get(uuid));
	}
}
