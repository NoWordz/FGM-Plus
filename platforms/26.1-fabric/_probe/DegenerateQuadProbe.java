import com.wildfire.api.uvs.UVLayout;
import com.wildfire.api.uvs.UVQuad;
import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.render.RoundBreastMesh;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Reproduces the Photon-black-chest report offline with the USER'S REAL config:
 * bustSize 0.964 (physics off, static), shape {scale 1.29/1.11/1.51, perkiness 0.1978,
 * roundness 0.1582, cleavage 1.0}, uniboob, offset 0.
 *
 * Replicates:
 * 1. the full setupTransformations pose chain (upstream GenderLayer.java:200-234 +
 *    our GenderLayerMixin fp$capture zOffset freeze + fp$applyShape tail),
 * 2. our BreastModelMixin roundness mesh emission + back-flatten pull,
 * 3. IRIS's MixinBufferBuilder.fillExtendedData: per-quad recomputed face normal via
 *    NormalHelper.computeFaceNormalManual (DIAGONAL cross (v2-v0)x(v3-v1) + JOML
 *    normalize — NaN on zero vector), packed with NormI8 semantics (clamp *127),
 *    overwriting our per-vertex normals.
 *
 * Reports per quad: pulled vertices, body-space z, recomputed normal, degeneracy,
 * and front-view visibility (is the quad's pixel covered by the torso front face
 * or by another part of this same mesh in front of it?).
 */
public class DegenerateQuadProbe {

    static final float DEG = (float) (Math.PI / 180);

    public static void main(String[] args) {
        // --- user's real skin UV layout (left breast, from
        //     config/FemaleGenderMod/<uuid>.json uvs.skin.left, UVLayout ctor order = (east, west, down, up, north)) ---
        UVLayout skin = new UVLayout(
                new UVQuad(24, 21, 27, 26),   // east
                new UVQuad(16, 21, 20, 26),   // west
                new UVQuad(20, 17, 24, 21),   // down
                new UVQuad(20, 25, 24, 27),   // up
                new UVQuad(20, 21, 24, 26));  // north
        WildfireModelRenderer.ModelBox box = new WildfireModelRenderer.BreastModelBox(64, 64, -4F, 0F, 0F, 4, 5, 3, 0.0F, skin);

        float roundness = 0.15821917F;
        RoundBreastMesh mesh = RoundBreastMesh.of(box, roundness, true);
        System.out.println("mesh quads=" + mesh.quadCount);

        // --- pose chain, local(px) -> body(px). Established from GenderLayer.setupTransformations
        // with: breastSize=1.228 (bSize 0.964 + 0.5*|0.964-0.7|*2), rotation clamp -> tilt -35deg,
        // zOffset frozen at 0.0125 by our fp$capture, shape offsets 0, uniboob (no +-0.125 translates),
        // physics static 0, breathing 0, outwardAngle=0 (FGM cleavage=0).
        // M = T(sink+hang) * Rx(-35) * S(0.9995,1,1) * Rx(+11.33 perkiness) * S(1.29, 1.292, 1.758)
        // (posestack post-multiplies; LAST op applied FIRST to the vertex)
        Matrix4f M = new Matrix4f()
                .translate(0, 0.212f, -1.8f)          // hang(-0.688px) + sink y0.9px/z-1.8px  (px)
                .rotateX(-35f * DEG)                  // droop
                .scale(0.9995f, 1f, 1f)               // FGM z-fight fix
                .rotateX((float) (0.19777398 * 180 / Math.PI) * DEG) // perkiness
                .scale(1.2917032f, 1.1127964f * 1.164f, 1.510367f * 1.164f); // shape * realScale(0.964)

        float PLANE_Z = 2.125f;   // fp$CLAMP_BACK_Z in body px
        int pulledVerts = 0;
        int zeroNormalQuads = 0, nanNormalQuads = 0;
        int visibleZeroQuads = 0, visibleNanQuads = 0;
        float maxZ = Float.NEGATIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;

        float[] d = mesh.data;
        for(int quad = 0; quad < mesh.quadCount; quad++) {
            float[] px = new float[12]; float[] py = new float[12]; float[] pz = new float[12];
            boolean pulled = false;
            for(int v = 0; v < 4; v++) {
                int o = quad * 32 + v * 8;
                Vector4f body = new Vector4f(d[o] * 16, d[o + 1] * 16, d[o + 2] * 16, 1.0F).mul(M);
                float bx = body.x(), by = body.y(), bz = body.z();
                if(bz > PLANE_Z) {
                    float depth = bz - PLANE_Z;
                    // pull along body -z (back->plane), viewed from front this moves vertices backward
                    bz = PLANE_Z;
                    pulled = true;
                    pulledVerts++;
                }
                px[v] = bx; py[v] = by; pz[v] = bz;
                minZ = Math.min(minZ, bz); maxZ = Math.max(maxZ, bz);
            }
            if(!pulled && maxZisInvisibleBand(pz)) { /* noop, kept for clarity */ }
            // IRIS computeFaceNormalManual: diagonals (v2-v0) x (v3-v1)
            float dx0 = px[2] - px[0], dy0 = py[2] - py[0], dz0 = pz[2] - pz[0];
            float dx1 = px[3] - px[1], dy1 = py[3] - py[1], dz1 = pz[3] - pz[1];
            float nx = dy0 * dz1 - dz0 * dy1;
            float ny = dz0 * dx1 - dx0 * dz1;
            float nz = dx0 * dy1 - dy0 * dx1;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            float rnx = 0, rny = 0, rnz = 0;
            if(len > 1e-20f) { rnx = nx / len; rny = ny / len; rnz = nz / len; }
            boolean zero = len < 1e-6f;
            // packed via clamp(-1,1)*127 like NormI8: NaN -> (int)NaN -> 0
            float pkx = clampByte(rnx), pky = clampByte(rny), pkz = clampByte(rnz);
            boolean packedZero = pkx == 0 && pky == 0 && pkz == 0;
            // front-view visibility: the quad's pixel column is occluded by the torso front
            // face (|x|<2 && z < +2 ... i.e. anything with z> -2 and |x|<4px sits behind the
            // torso front face at z=-2px only if something opaque is in front; the torso front
            // face is at z=-2px, so any geometry with z > -2px whose |x|<4.09 and y within the
            // torso y-range 0..12 is BEHIND the torso front face from a straight-front camera).
            float cx = (px[0] + px[1] + px[2] + px[3]) / 4;
            float cy = (py[0] + py[1] + py[2] + py[3]) / 4;
            float cz = (pz[0] + pz[1] + pz[2] + pz[3]) / 4;
            boolean frontVisibleFromCamera = cz < -2.0f           // in front of the torso front face
                    || Math.abs(cx) > 4.0f                         // sticks out sideways of the 8px-wide torso
                    || cy < 0f || cy > 12f;                        // above/below the torso
            if(zero || packedZero) {
                zeroNormalQuads++;
                if(frontVisibleFromCamera) visibleZeroQuads++;
                System.out.printf("ZERO-NORMAL quad %d: pulled=%s center body=(%.2f, %.2f, %.2f)px frontVisible=%s n=(%.4f,%.4f,%.4f) len=%.2e%n",
                        quad, pulled, cx, cy, cz, frontVisibleFromCamera, rnx, rny, rnz, len);
            }
            if(pulled) {
                System.out.printf("pulled quad %d: center body=(%.2f, %.2f, %.2f)px frontVisible=%s zeroNormal=%s%n",
                        quad, cx, cy, cz, frontVisibleFromCamera, zero || packedZero);
            }
        }
        System.out.println("pulledVerts=" + pulledVerts + " zeroNormalQuads=" + zeroNormalQuads
                + " visibleZeroQuads=" + visibleZeroQuads + " zRange=[" + minZ + "," + maxZ + "]");
    }

    static boolean maxZisInvisibleBand(float[] pz) { return false; }

    static float clampByte(float v) {
        if(Float.isNaN(v)) return 0f;
        return (float) ((int) (Math.max(-1f, Math.min(1f, v)) * 127f)) / 127f;
    }
}
