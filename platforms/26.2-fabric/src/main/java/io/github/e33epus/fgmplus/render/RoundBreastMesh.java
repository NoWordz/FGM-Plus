package io.github.e33epus.fgmplus.render;

import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.FgmPlusMod;
import io.github.e33epus.fgmplus.config.FgmPlusConfig;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Turns FGM's flat 4x5x3 breast box into a superellipsoid mesh — the fix for the
 * "triangle" silhouette (a 3px-deep box tilted forward by -35deg reads as a wedge).
 *
 * <p>Deformation is a radial projection onto the superellipsoid
 * {@code |x|^n + |y|^n + |z|^n = 1} in the box's normalized local space, with
 * {@code n = 2 / roundness}: n is +infinity for a box, 2 for a sphere, so the
 * roundness slider walks the box→sphere continuum and every intermediate value
 * is a rounded box. Because the projection is continuous, adjacent faces share
 * identical boundary vertices — no seams; face centers stay on the box surface
 * (axial points project onto themselves), so a slightly rounded breast keeps
 * sitting on the torso and only its edges pull in.</p>
 *
 * <p>Vertex normals use the superellipsoid gradient
 * {@code (sign(x)|x|^(n-1), ...)} normalized — the analytic surface normal, so
 * lighting is smooth across subdivided faces and continuous across edges.</p>
 *
 * <p>UVs interpolate inside each original quad's (already user-editable) corner
 * UVs, so FGM's UV editor output maps unchanged onto the curved surface.</p>
 *
 * <p>Subdivision density follows the box dimensions (~one segment per model
 * pixel), so a 4x5x3 box becomes ~90 quads. That is only worth computing when
 * the box or the roundness changes: meshes are cached per box instance
 * (FGM rebuilds its boxes when a player's UV layout changes, producing a fresh
 * key) and regenerated when the roundness differs. Rendering is single-threaded,
 * matching the WeakHashMap use in {@link RenderCapture}.</p>
 */
public final class RoundBreastMesh {

	/** Flat interleaved vertices: [x, y, z, nx, ny, nz, u, v] x 4 per quad, world units (px/16). */
	public final float[] data;
	public final int quadCount;

	private final float roundness;

	//Cleavage bridge strength: how far the inner half-axis may grow past the box
	//face at full roundness (fraction of hx). The two bridged surfaces meet ON
	//the torso centerline and interpenetrate slightly, so the void between the
	//rounds closes into a crease the clothes texture spans.
	private static final float CLEAVAGE_BRIDGE = 0.75F;
	private static final float UV_CELL_INSET = 0.25F;

	//box instance -> roundness -> mesh. Boxes are SHARED across players with the same
	//UV layout (GenderLayer keeps them as layer fields), and roundness is per player, so
	//a single slot per box made players with different roundness rebuild the mesh every
	//frame while they were both on screen. A few slots cover any realistic scene; the
	//slider drag path (a new value every frame) clears instead of growing unbounded.
	private static final Map<WildfireModelRenderer.ModelBox, Map<Float, RoundBreastMesh>> CACHE =
			Collections.synchronizedMap(new WeakHashMap<>());

	private RoundBreastMesh(float[] data, int quadCount, float roundness) {
		this.data = data;
		this.quadCount = quadCount;
		this.roundness = roundness;
	}

	public record DepthFit(Matrix4f pose, float maxPull) {}

	/**
	 * Fits the curved mesh against the back plane with a single affine transform.
	 * Shared edges stay joined, and the front stays in place when there is room to fit.
	 * Returns null when the mesh already fits.
	 */
	public DepthFit fitBackPlane(Matrix4fc pose, Matrix4fc bodyView, Matrix4fc bodyInverse, float planeZ) {
		Matrix4f modelToBody = bodyInverse.mul(pose, new Matrix4f());
		float minZ = Float.POSITIVE_INFINITY;
		float maxZ = Float.NEGATIVE_INFINITY;
		for(int i = 0; i < data.length; i += 8) {
			float z = modelToBody.m02() * data[i] + modelToBody.m12() * data[i + 1]
					+ modelToBody.m22() * data[i + 2] + modelToBody.m32();
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		if(!(maxZ > planeZ)) {
			return null;
		}
		// If the front is close to the plane, shift the remaining overflow forward
		// rather than crushing the mesh to a plane and making its normals singular.
		float depth = maxZ - minZ;
		float scaleZ = depth > 0.0F ? Math.max(0.5F, (planeZ - minZ) / depth) : 1.0F;
		Matrix4f fitInBody = new Matrix4f().translation(0.0F, 0.0F, planeZ - maxZ * scaleZ)
				.scale(1.0F, 1.0F, scaleZ).mul(modelToBody);
		return new DepthFit(bodyView.mul(fitInBody, new Matrix4f()), maxZ - planeZ);
	}

	/**
	 * Cached mesh of {@code box} at {@code roundness}; never null for roundness > 0.
	 * {@code bridged} toggles the cleavage bridge; it rides in the cache key's
	 * integer part (roundness stays below 1, the off-state adds 10).
	 */
	public static RoundBreastMesh of(WildfireModelRenderer.ModelBox box, float roundness, boolean bridged) {
		// Include the UV mode so changing it during a debug reload takes effect.
		boolean fullUv = FgmPlusConfig.meshDebugFullUv;
		boolean insetUv = !fullUv && FgmPlusConfig.meshTexelCenterUv;
		float key = (bridged ? roundness : 10.0F + roundness);
		if(FgmPlusConfig.meshDebugNoMorph) key += 20.0F;
		if(fullUv) key += 40.0F;
		if(insetUv) key += 80.0F;
		Map<Float, RoundBreastMesh> slots = CACHE.computeIfAbsent(box, k -> new java.util.HashMap<>());
		synchronized(slots) {
			if(slots.size() > 8) {
				slots.clear();
			}
			RoundBreastMesh mesh = slots.get(key);
			if(mesh == null) {
				mesh = build(box, roundness, bridged, fullUv, insetUv);
				slots.put(key, mesh);
				//cache-miss only; the first-line observability for
				//"the roundness slider does nothing" reports
				FgmPlusMod.LOGGER.debug("FGM Plus built round mesh: quads={}, roundness={}", mesh.quadCount, roundness);
			}
			return mesh;
		}
	}

	private static RoundBreastMesh build(WildfireModelRenderer.ModelBox box, float roundness, boolean bridged,
			boolean fullUv, boolean insetUv) {
		//exponent for the box->sphere continuum: roundness 1 -> n=2 (sphere);
		//clamped away from 0 so callers can pass small values without pow blowups
		float r = Math.max(roundness, 0.01F);
		double n = 2.0 / r;
		//diagnostic: keep the subdivided grid on the ORIGINAL box surface (bisection
		//for the shader-only black-chest report) with the source face's flat normal
		boolean noMorph = FgmPlusConfig.meshDebugNoMorph;

		//bounding box in model pixels, from the actual quad vertices (the pos* fields
		//predate the inflate delta and can disagree by it)
		float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
		int usable = 0;
		for(WildfireModelRenderer.TexturedQuad quad : box.quads) {
			if(quad == null || isDegenerate(quad)) continue;
			usable++;
			for(WildfireModelRenderer.PositionTextureVertex v : quad.vertexPositions) {
				minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
				minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
				minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
			}
		}
		if(usable == 0) {
			return new RoundBreastMesh(new float[0], 0, roundness);
		}
		float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, cz = (minZ + maxZ) / 2f;
		float hx = Math.max((maxX - minX) / 2f, 1.0e-4f);
		float hy = Math.max((maxY - minY) / 2f, 1.0e-4f);
		float hz = Math.max((maxZ - minZ) / 2f, 1.0e-4f);
		//which side of this box faces the torso centerline (part-local x=0): a
		//box on the -x half bridges toward +x and vice versa; 0 disables the
		//bridge for a box centered on the centerline (breast boxes never are)
		float bridgeSign = Math.abs(cx) < 1.0e-3f ? 0.0F : -Math.signum(cx);
		float ext = bridged ? 1.0F + CLEAVAGE_BRIDGE * r : 1.0F;

		//each subdivided face quad is [x, y, z, nx, ny, nz, u, v] x 4; one source face
		//yields segS x segT sub-quads, so the total is counted before allocating
		int totalQuads = 0;
		for(WildfireModelRenderer.TexturedQuad src : box.quads) {
			if(src == null || isDegenerate(src)) continue;
			WildfireModelRenderer.PositionTextureVertex[] v = src.vertexPositions;
			totalQuads += segments(edgeLength(v[0], v[1])) * segments(edgeLength(v[0], v[3]));
		}
		float[] out = new float[totalQuads * 4 * 8];
		int quad = 0;
		for(WildfireModelRenderer.TexturedQuad src : box.quads) {
			if(src == null || isDegenerate(src)) continue;
			WildfireModelRenderer.PositionTextureVertex[] c = src.vertexPositions;
			//segment count follows the face's pixel size along each edge, capped so a
			//pathologically large box cannot blow up the vertex count
			float ax = c[0].x(), ay = c[0].y(), az = c[0].z();
			float bx = c[1].x(), by = c[1].y(), bz = c[1].z();
			float dx = c[2].x(), dy = c[2].y(), dz = c[2].z();
			float ex = c[3].x(), ey = c[3].y(), ez = c[3].z();
			int segS = segments(Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az));
			int segT = segments(Math.abs(ex - ax) + Math.abs(ey - ay) + Math.abs(ez - az));
			for(int i = 0; i < segS; i++) {
				float s0 = (float) i / segS, s1 = (float) (i + 1) / segS;
				for(int j = 0; j < segT; j++) {
					float t0 = (float) j / segT, t1 = (float) (j + 1) / segT;
					//corner order matches the source quad's winding: (a, b, d/c cell corners)
					float uA, vA, uB, vB, uC, vC, uD, vD;
					if(fullUv) {
						uA = c[0].u(); vA = c[0].v();
						uB = c[1].u(); vB = c[1].v();
						uC = c[2].u(); vC = c[2].v();
						uD = c[3].u(); vD = c[3].v();
					} else {
						// Sample inside each cell. Rounding corners independently can fetch
						// the next face's pixels or give Iris a zero-area UV triangle.
						float sInset = insetUv ? UV_CELL_INSET * (s1 - s0) : 0.0F;
						float tInset = insetUv ? UV_CELL_INSET * (t1 - t0) : 0.0F;
						float uvS0 = s0 + sInset, uvS1 = s1 - sInset;
						float uvT0 = t0 + tInset, uvT1 = t1 - tInset;
						uA = lerp(lerp(c[0].u(), c[1].u(), uvS0), lerp(c[3].u(), c[2].u(), uvS0), uvT0);
						vA = lerp(lerp(c[0].v(), c[1].v(), uvS0), lerp(c[3].v(), c[2].v(), uvS0), uvT0);
						uB = lerp(lerp(c[0].u(), c[1].u(), uvS1), lerp(c[3].u(), c[2].u(), uvS1), uvT0);
						vB = lerp(lerp(c[0].v(), c[1].v(), uvS1), lerp(c[3].v(), c[2].v(), uvS1), uvT0);
						uC = lerp(lerp(c[0].u(), c[1].u(), uvS1), lerp(c[3].u(), c[2].u(), uvS1), uvT1);
						vC = lerp(lerp(c[0].v(), c[1].v(), uvS1), lerp(c[3].v(), c[2].v(), uvS1), uvT1);
						uD = lerp(lerp(c[0].u(), c[1].u(), uvS0), lerp(c[3].u(), c[2].u(), uvS0), uvT1);
						vD = lerp(lerp(c[0].v(), c[1].v(), uvS0), lerp(c[3].v(), c[2].v(), uvS0), uvT1);
					}
					emit(out, quad * 32,
							resolvePoint(noMorph, src, corner(ax, ay, az, bx, by, bz, ex, ey, ez, dx, dy, dz, s0, t0), cx, cy, cz, hx, hy, hz, n, bridgeSign, ext),
							resolvePoint(noMorph, src, corner(ax, ay, az, bx, by, bz, ex, ey, ez, dx, dy, dz, s1, t0), cx, cy, cz, hx, hy, hz, n, bridgeSign, ext),
							resolvePoint(noMorph, src, corner(ax, ay, az, bx, by, bz, ex, ey, ez, dx, dy, dz, s1, t1), cx, cy, cz, hx, hy, hz, n, bridgeSign, ext),
							resolvePoint(noMorph, src, corner(ax, ay, az, bx, by, bz, ex, ey, ez, dx, dy, dz, s0, t1), cx, cy, cz, hx, hy, hz, n, bridgeSign, ext),
							uA, vA, uB, vB, uC, vC, uD, vD);
					quad++;
				}
			}
		}
		return new RoundBreastMesh(out, quad, roundness);
	}

	/**
	 * Diagnostic bisection hook: {@code noMorph} keeps the subdivided grid on the
	 * original box surface with the source face's flat normal, skipping the
	 * superellipsoid projection and the cleavage bridge entirely.
	 */
	private static float[] resolvePoint(boolean noMorph, WildfireModelRenderer.TexturedQuad src, float[] corner,
			float cx, float cy, float cz, float hx, float hy, float hz, double n, float bridgeSign, float ext) {
		if(noMorph) {
			return new float[]{corner[0], corner[1], corner[2], src.normal.x(), src.normal.y(), src.normal.z()};
		}
		return projectBridged(corner, cx, cy, cz, hx, hy, hz, n, bridgeSign, ext);
	}

	/**
	 * Cleavage-bridged projection. The pure superellipsoid pulls each box's
	 * inner face in as it rounds, leaving a wedge-shaped void on the torso
	 * centerline — a hole no clothes texture can cover. Blending the unmodified
	 * projection with one whose x half-axis is extended by {@code ext} (weight 0
	 * at the outer face, 1 at the centerline face, smoothstep between) pushes
	 * the inner half past the box face, so the two bridged surfaces interpenetrate
	 * on the centerline: the intersection reads as a natural crease and the skin
	 * texture (the clothes) spans it. Position and normal are blended separately,
	 * the normal re-normalized.
	 */
	private static float[] projectBridged(float[] p, float cx, float cy, float cz,
			float hx, float hy, float hz, double n, float bridgeSign, float ext) {
		float[] sym = project(p, cx, cy, cz, hx, hy, hz, n);
		if(bridgeSign == 0.0F) {
			return sym;
		}
		float[] bulged = project(p, cx, cy, cz, hx * ext, hy, hz, n);
		float t = ((p[0] - cx) / hx * bridgeSign + 1.0F) * 0.5F;
		float w = t <= 0.0F ? 0.0F : t >= 1.0F ? 1.0F : t * t * (3.0F - 2.0F * t);
		float x = lerp(sym[0], bulged[0], w);
		float y = lerp(sym[1], bulged[1], w);
		float z = lerp(sym[2], bulged[2], w);
		float nx = lerp(sym[3], bulged[3], w);
		float ny = lerp(sym[4], bulged[4], w);
		float nz = lerp(sym[5], bulged[5], w);
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if(len < 1.0e-6f) {
			return new float[]{x, y, z, 0f, 1f, 0f};
		}
		return new float[]{x, y, z, nx / len, ny / len, nz / len};
	}

	private static boolean isDegenerate(WildfireModelRenderer.TexturedQuad quad) {
		//FGM's own render loop skips quads whose UVs are all zero
		return quad.uvs[0] == 0.0F && quad.uvs[1] == 0.0F && quad.uvs[2] == 0.0F && quad.uvs[3] == 0.0F;
	}

	private static int segments(float pixelLength) {
		return Math.max(1, Math.min(8, Math.round(pixelLength)));
	}

	private static float edgeLength(WildfireModelRenderer.PositionTextureVertex a, WildfireModelRenderer.PositionTextureVertex b) {
		return Math.abs(b.x() - a.x()) + Math.abs(b.y() - a.y()) + Math.abs(b.z() - a.z());
	}

	/** Bilinear position on the source face quad; corner winding follows the quad's own vertex order. */
	private static float[] corner(float ax, float ay, float az, float bx, float by, float bz,
			float ex, float ey, float ez, float dx, float dy, float dz, float s, float t) {
		float abx = ax + (bx - ax) * s, aby = ay + (by - ay) * s, abz = az + (bz - az) * s;
		float edx = ex + (dx - ex) * s, edy = ey + (dy - ey) * s, edz = ez + (dz - ez) * s;
		return new float[]{abx + (edx - abx) * t, aby + (edy - aby) * t, abz + (edz - abz) * t};
	}

	/**
	 * Radial superellipsoid projection: normalized direction from the box center is
	 * scaled down onto the {@code sum|x|^n = 1} surface, then mapped back to world
	 * units. The returned array is [x, y, z, nx, ny, nz] in model pixels.
	 */
	private static float[] project(float[] p, float cx, float cy, float cz,
			float hx, float hy, float hz, double n) {
		double ux = (p[0] - cx) / hx, uy = (p[1] - cy) / hy, uz = (p[2] - cz) / hz;
		double len = Math.pow(Math.pow(Math.abs(ux), n) + Math.pow(Math.abs(uy), n) + Math.pow(Math.abs(uz), n), 1.0 / n);
		//inputs sit on the box surface, so len >= 1 > 0; keep the guard anyway
		if(len < 1.0e-6) {
			return new float[]{p[0], p[1], p[2], 0f, 1f, 0f};
		}
		double sx = ux / len, sy = uy / len, sz = uz / len;

		//analytic superellipsoid normal: gradient of sum|x|^n, normalized. The surface
		//is the unit one stretched by (hx, hy, hz), so the gradient maps with the
		//inverse-transpose scale (per-axis divide) to stay perpendicular to the real,
		//non-uniform surface
		double px = Math.pow(Math.abs(sx), n - 1.0) * Math.signum(sx) / hx;
		double py = Math.pow(Math.abs(sy), n - 1.0) * Math.signum(sy) / hy;
		double pz = Math.pow(Math.abs(sz), n - 1.0) * Math.signum(sz) / hz;
		double gl = Math.sqrt(px * px + py * py + pz * pz);
		float nx, ny, nz;
		if(gl < 1.0e-6) {
			nx = 0f; ny = 1f; nz = 0f;
		} else {
			nx = (float) (px / gl); ny = (float) (py / gl); nz = (float) (pz / gl);
		}
		return new float[]{
				(float) (cx + sx * hx), (float) (cy + sy * hy), (float) (cz + sz * hz),
				nx, ny, nz
		};
	}

	private static void emit(float[] out, int base,
			float[] p0, float[] p1, float[] p2, float[] p3,
			float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
		write(out, base, p0, u0, v0);
		write(out, base + 8, p1, u1, v1);
		write(out, base + 16, p2, u2, v2);
		write(out, base + 24, p3, u3, v3);
	}

	private static void write(float[] out, int o, float[] p, float u, float v) {
		out[o] = p[0] / 16f; out[o + 1] = p[1] / 16f; out[o + 2] = p[2] / 16f;
		out[o + 3] = p[3]; out[o + 4] = p[4]; out[o + 5] = p[5];
		out[o + 6] = u; out[o + 7] = v;
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** Validation hook for the offline math probe; not on any runtime path. Returns [x, y, z, nx, ny, nz]. */
	public static float[] probeProject(float roundness, float px, float py, float pz, float hx, float hy, float hz) {
		return project(new float[]{px, py, pz}, 0f, 0f, 0f, hx, hy, hz, 2.0 / Math.max(roundness, 0.01F));
	}
}
