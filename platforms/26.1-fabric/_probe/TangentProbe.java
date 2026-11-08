import com.wildfire.api.uvs.UVLayout;
import com.wildfire.api.uvs.UVQuad;
import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.render.RoundBreastMesh;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Replicates IRIS MixinBufferBuilder#fillExtendedData on the USER'S REAL mesh+pose:
 * per-quad face normal (diagonal cross, as before) AND computeTangent (tri 012 +
 * UV gradient) — the data Photon's tbn/flat_normal is built from. The chained
 * (vanilla addVertex) path is what these values go through, so any degenerate
 * tangent here = black chest under Photon while vanilla stays fine.
 */
public class TangentProbe {

    static final float DEG = (float) (Math.PI / 180);

    public static void main(String[] args) {
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

        Matrix4f M = new Matrix4f()
                .translate(0, 0.212f, -1.8f)
                .rotateX(-35f * DEG)
                .scale(0.9995f, 1f, 1f)
                .rotateX((float) (0.19777398 * 180 / Math.PI) * DEG)
                .scale(1.2917032f, 1.1127964f * 1.164f, 1.510367f * 1.164f);

        float PLANE_Z = 2.125f;
        int badDenom = 0, zeroTan = 0, badNormal = 0;

        float[] d = mesh.data;
        for(int quad = 0; quad < mesh.quadCount; quad++) {
            float[] px = new float[4], py = new float[4], pz = new float[4];
            float[] pu = new float[4], pv = new float[4];
            for(int v = 0; v < 4; v++) {
                int o = quad * 32 + v * 8;
                Vector4f body = new Vector4f(d[o] * 16, d[o + 1] * 16, d[o + 2] * 16, 1.0F).mul(M);
                float bz = body.z();
                if(bz > PLANE_Z) bz = PLANE_Z; // per-quad rigid pull maps deepest to plane; approximation ok for diag check
                px[v] = body.x(); py[v] = body.y(); pz[v] = bz;
                pu[v] = d[o + 6]; pv[v] = d[o + 7];
            }
            // face normal (diagonal cross v2-v0 x v3-v1) — Iris computeFaceNormalManual
            float dx0 = px[2] - px[0], dy0 = py[2] - py[0], dz0 = pz[2] - pz[0];
            float dx1 = px[3] - px[1], dy1 = py[3] - py[1], dz1 = pz[3] - pz[1];
            float nx = dy0 * dz1 - dz0 * dy1;
            float ny = dz0 * dx1 - dx0 * dz1;
            float nz = dx0 * dy1 - dy0 * dx1;
            float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if(nlen < 1e-6f) {
                badNormal++;
                System.out.printf("quad %d DEGENERATE FACE NORMAL len=%.2e%n", quad, nlen);
                continue;
            }
            nx /= nlen; ny /= nlen; nz /= nlen;

            // computeTangent (tri 012) — Iris NormalHelper, UV gradient
            float edge1x = px[1] - px[0], edge1y = py[1] - py[0], edge1z = pz[1] - pz[0];
            float edge2x = px[2] - px[0], edge2y = py[2] - py[0], edge2z = pz[2] - pz[0];
            float du1 = pu[1] - pu[0], dv1 = pv[1] - pv[0];
            float du2 = pu[2] - pu[0], dv2 = pv[2] - pv[0];
            float fdenom = du1 * dv2 - du2 * dv1;
            float f = fdenom == 0.0f ? 1.0f : 1.0f / fdenom;
            float tx = f * (dv2 * edge1x - dv1 * edge2x);
            float ty = f * (dv2 * edge1y - dv1 * edge2y);
            float tz = f * (dv2 * edge1z - dv1 * edge2z);
            float tlen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if(fdenom == 0.0f) badDenom++;
            if(tlen < 1e-6f || Float.isNaN(tlen)) {
                zeroTan++;
                System.out.printf("quad %d BAD TANGENT denom=%.3e tlen=%.2e uv=(%.3f,%.3f)(%.3f,%.3f)(%.3f,%.3f)%n",
                        quad, fdenom, tlen, pu[0], pv[0], pu[1], pv[1], pu[2], pv[2]);
            } else {
                // how far off the tangent plane is it? tangent must be perpendicular to normal
                float dot = tx / tlen * nx + ty / tlen * ny + tz / tlen * nz;
                if(Math.abs(dot) > 0.3f) {
                    System.out.printf("quad %d TANGENT NOT PERP dot=%.3f denom=%.3e%n", quad, dot, fdenom);
                }
            }
        }
        System.out.println("badDenom(=0,f=1)=" + badDenom + " zeroTan=" + zeroTan + " badNormal=" + badNormal);

        // UV dump: interpolated sub-quad UVs vs source face rect + [0,1] range
        float minU=1e9f,maxU=-1e9f,minV=1e9f,maxV=-1e9f;
        int outOfRange=0, nan=0;
        for(int quad = 0; quad < mesh.quadCount; quad++) {
            for(int v = 0; v < 4; v++) {
                int o = quad * 32 + v * 8;
                float u = d[o+6], vv = d[o+7];
                if(Float.isNaN(u)||Float.isNaN(vv)) { nan++; continue; }
                minU=Math.min(minU,u); maxU=Math.max(maxU,u);
                minV=Math.min(minV,vv); maxV=Math.max(maxV,vv);
                if(u<0||u>1||vv<0||vv>1) outOfRange++;
            }
        }
        System.out.printf("UV range u=[%.4f,%.4f] v=[%.4f,%.4f] outOf[0,1]=%d nan=%d%n", minU,maxU,minV,maxV,outOfRange,nan);
        // print first 3 quads' UVs raw (atlas units? check magnitude)
        for(int quad = 0; quad < 3; quad++) {
            StringBuilder sb = new StringBuilder("quad "+quad+" uv: ");
            for(int v = 0; v < 4; v++) {
                int o = quad * 32 + v * 8;
                sb.append(String.format("(%.3f,%.3f)", d[o+6], d[o+7]));
            }
            System.out.println(sb);
        }
    }
}
