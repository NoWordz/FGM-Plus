package io.github.e33epus.fgmplus.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.e33epus.fgmplus.FgmPlusMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain JSON config (config/fgmplus/fgmplus.json), replacing the Forge 1.20.1 TOML spec.
 *
 * <p>Loaded lazily on first access: the mixin that widens FGM's slider keys reads these
 * values while FGM's own {@code Configuration} class initializes, which can happen before
 * any mod entrypoint runs. Missing or malformed keys fall back to the spec defaults.</p>
 */
public final class FgmPlusConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// Slider range widenings; each entry clamps to the same bounds the Forge spec enforced
	public static double bustSizeMax = 4.0;        // [0.8, 4.0]; 4.0 tops FGM's x125 percent display at 500%, matching the Forge default
	public static double bustOffsetYMin = -1.5;    // [-4.0, -1.0], FGM default bound -1.0
	public static double bustOffsetZMin = -1.5;    // [-4.0, -1.0], FGM default bound -1.0
	public static double bustOffsetZMax = 0.0;     // [0.0, 4.0], FGM default bound 0.0
	public static double bounceMultiplierMax = 1.0;  // [0.5, 3.0], FGM default bound 0.5
	public static double floppyMultiplierMax = 1.5;  // [1.0, 3.0], FGM default bound 1.0

	// Back-flatten pull strategy for the roundness mesh. Iris recomputes entity face
	// normals from vertex positions (MixinBufferBuilder#fillExtendedData overwrites what
	// we write), so a per-vertex pull stretches curved quads and their recomputed
	// normals rotate away from the surface - under directional-lighting shader packs
	// (Photon etc.) those quads render black; vanilla's 0.4 ambient floor hides it.
	// per_quad translates each quad rigidly by its deepest vertex's depth, keeping the
	// shape (and therefore the recomputed normal) intact. "off" disables the pull.
	public static String meshPullMode = "per_quad"; // per_quad | per_vertex | off
	public static boolean meshDebugLog = false;     // throttled pull stats for diagnosing shader-only artifacts
	// Render the roundness mesh as the flat box in Iris's shadow pass. Vanilla entity
	// geometry is all planar boxes, so shader packs tune their shadow-map normal bias
	// for planar depth gradients; a curved entity surface self-shadows (acne) because
	// the per-texel depth varies beyond what the bias compensates. The shadow
	// silhouette barely changes, the lit chest stops eating itself.
	public static boolean meshShadowFlat = true;

	//Bisection switches for the shader-only black-chest report: each disables one
	//difference between the roundness mesh and the flat path that renders fine.
	//Diagnostics only - delete once the root cause is pinned.
	public static boolean meshDebugNoMorph = false;   // mesh keeps box positions (subdivision + UV interpolation only)
	public static boolean meshDebugNoBridge = false;  // mesh builds without the cleavage bridge
	public static int meshDebugMaxQuads = -1;         // emit only the first N quads of the mesh (-1 = all); count sweep
	public static boolean meshDebugFullUv = false;   // each sub-quad gets the source face's full corner UVs (smeared texture, diagnostic)
	//Snap every sub-quad's corners to the center of the texel it covers. The exact
	//1-texel sub-quad UVs sit ON atlas texel boundaries, and shader packs' mipmapped
	//atlas sampling renders those fragments black (vanilla's un-mipped sampling is
	//fine) - the Photon black-chest report. Texel-center UVs keep the same 1:1 texel
	//mapping but every sample lands inside the texel. fullUv (whole face rect per
	//sub-quad) renders lit in-game, confirming the sampling-side mechanism.
	public static boolean meshTexelCenterUv = true;

	private static boolean loaded;
	private static long lastMtime = -1L;
	private static long lastCheckMs;

	static {
		// load as early as possible: the ConfigurationMixin @Shadow initializers read these
		// fields while FGM's Configuration class initializes, which can predate any mod entrypoint
		load();
	}

	private FgmPlusConfig() {
	}

	public static void setup() {
		load();
	}

	/**
	 * Debug-loop convenience: re-reads the config if the file changed on disk, at most
	 * once a second. Called from the render path so bisection switches apply without a
	 * game restart.
	 */
	public static void maybeReload() {
		long now = System.currentTimeMillis();
		if(now - lastCheckMs < 1000L) {
			return;
		}
		lastCheckMs = now;
		try {
			long mtime = Files.getLastModifiedTime(file()).toMillis();
			if(mtime != lastMtime) {
				loaded = false;
				load();
			}
		} catch(Throwable ignored) {
			//headless probe or unreadable file: keep current values
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FgmPlusMod.MODID).resolve(FgmPlusMod.MODID + ".json");
	}

	private static synchronized void load() {
		if(loaded) return;
		loaded = true;
		//headless/offline safety (probe JVMs have no loader config dir): bail instead of NPE
		Path file;
		try {
			file = file();
		} catch(Throwable t) {
			return;
		}
		//config lives at config/fgmplus/fgmplus.json since 1.6.1 (was fgmplus.json in
		//the config root); carry the old file over so slider-widening settings survive
		try {
			Path legacy = FabricLoader.getInstance().getConfigDir().resolve(FgmPlusMod.MODID + ".json");
			if(!Files.exists(file) && Files.isRegularFile(legacy)) {
				Files.move(legacy, file);
			}
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: could not migrate the old config file", e);
		}
		if(Files.isRegularFile(file)) {
			try(Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if(json != null) {
					bustSizeMax = clamp(opt(json, "bustSizeMax", bustSizeMax), 0.8, 4.0);
					bustOffsetYMin = clamp(opt(json, "bustOffsetYMin", bustOffsetYMin), -4.0, -1.0);
					bustOffsetZMin = clamp(opt(json, "bustOffsetZMin", bustOffsetZMin), -4.0, -1.0);
					bustOffsetZMax = clamp(opt(json, "bustOffsetZMax", bustOffsetZMax), 0.0, 4.0);
					bounceMultiplierMax = clamp(opt(json, "bounceMultiplierMax", bounceMultiplierMax), 0.5, 3.0);
					floppyMultiplierMax = clamp(opt(json, "floppyMultiplierMax", floppyMultiplierMax), 1.0, 3.0);
					meshPullMode = optPullMode(json);
					meshDebugLog = optBool(json, "meshDebugLog", meshDebugLog);
					meshShadowFlat = optBool(json, "meshShadowFlat", meshShadowFlat);
					meshDebugNoMorph = optBool(json, "meshDebugNoMorph", meshDebugNoMorph);
					meshDebugNoBridge = optBool(json, "meshDebugNoBridge", meshDebugNoBridge);
					meshDebugMaxQuads = (int) clamp(opt(json, "meshDebugMaxQuads", meshDebugMaxQuads), -1, 100000);
					meshDebugFullUv = optBool(json, "meshDebugFullUv", meshDebugFullUv);
					meshTexelCenterUv = optBool(json, "meshTexelCenterUv", meshTexelCenterUv);
				}
			} catch(Exception e) {
				FgmPlusMod.LOGGER.warn("FGM Plus: failed to read config, using defaults", e);
			}
		}
		save();
		try {
			lastMtime = Files.getLastModifiedTime(file()).toMillis();
		} catch(Throwable ignored) {
		}
	}

	private static synchronized void save() {
		Path file;
		try {
			file = file();
		} catch(Throwable t) {
			return; //headless probe: nothing to persist
		}
		try {
			Files.createDirectories(file.getParent());
			JsonObject json = new JsonObject();
			json.addProperty("bustSizeMax", bustSizeMax);
			json.addProperty("bustOffsetYMin", bustOffsetYMin);
			json.addProperty("bustOffsetZMin", bustOffsetZMin);
			json.addProperty("bustOffsetZMax", bustOffsetZMax);
		json.addProperty("bounceMultiplierMax", bounceMultiplierMax);
		json.addProperty("floppyMultiplierMax", floppyMultiplierMax);
		json.addProperty("meshPullMode", meshPullMode);
		json.addProperty("meshDebugLog", meshDebugLog);
		json.addProperty("meshShadowFlat", meshShadowFlat);
		json.addProperty("meshDebugNoMorph", meshDebugNoMorph);
		json.addProperty("meshDebugNoBridge", meshDebugNoBridge);
		json.addProperty("meshDebugMaxQuads", meshDebugMaxQuads);
		json.addProperty("meshDebugFullUv", meshDebugFullUv);
		json.addProperty("meshTexelCenterUv", meshTexelCenterUv);
			Files.writeString(file(), GSON.toJson(json), StandardCharsets.UTF_8);
		} catch(Exception e) {
			FgmPlusMod.LOGGER.warn("FGM Plus: failed to write config", e);
		}
	}

	private static double opt(JsonObject json, String key, double fallback) {
		try {
			return json.get(key).getAsDouble();
		} catch(Exception e) {
			return fallback;
		}
	}

	private static boolean optBool(JsonObject json, String key, boolean fallback) {
		try {
			return json.get(key).getAsBoolean();
		} catch(Exception e) {
			return fallback;
		}
	}

	private static String optPullMode(JsonObject json) {
		try {
			String value = json.get("meshPullMode").getAsString();
			if("per_quad".equals(value) || "per_vertex".equals(value) || "off".equals(value)) {
				return value;
			}
		} catch(Exception ignored) {
		}
		return meshPullMode;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

}
