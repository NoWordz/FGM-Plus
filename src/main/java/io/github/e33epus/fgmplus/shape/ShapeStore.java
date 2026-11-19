package io.github.e33epus.fgmplus.shape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Disk persistence for the local player's shape: config/fgmplus/shapes/&lt;UUID&gt;.json.
 * Default shapes are deleted instead of written so the directory only ever holds
 * customized players.
 */
public final class ShapeStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ShapeStore() {
	}

	public static Path getDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("fgmplus").resolve("shapes");
	}

	private static Path fileFor(UUID uuid) {
		return getDir().resolve(uuid.toString() + ".json");
	}

	/** Missing or corrupted file yields default values, never an exception. */
	public static ShapeData load(UUID uuid) {
		Path file = fileFor(uuid);
		if(!Files.isRegularFile(file)) {
			return new ShapeData();
		}
		try(Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			return ShapeData.fromJson(json);
		} catch(Exception e) {
			return new ShapeData();
		}
	}

	public static void save(UUID uuid, ShapeData shape) {
		try {
			Files.createDirectories(getDir());
			Path file = fileFor(uuid);
			if(shape == null || shape.isDefault()) {
				Files.deleteIfExists(file);
				return;
			}
			Files.writeString(file, GSON.toJson(shape.toJson()), StandardCharsets.UTF_8);
		} catch(IOException ignored) {
			//failed save keeps the previous file; no need to break the caller (GUI) on IO errors
		}
	}
}
