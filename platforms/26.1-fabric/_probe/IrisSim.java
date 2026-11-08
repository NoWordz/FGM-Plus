import com.wildfire.api.uvs.UVLayout;
import com.wildfire.api.uvs.UVQuad;
import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.render.RoundBreastMesh;

/** Replicates Iris fillExtendedData per quad (recomputed normal + tangent via tri 012) for the
 *  FLAT box emission vs the SUBDIVIDED no-morph mesh emission; flags garbage. */
public class IrisSim {
    static int pack(float x, float y, float z, float w) { // NormI8.pack: clamp *127 int
        int ix = packByte(x), iy = packByte(y), iz = packByte(z), iw = packByte(w);
        return (ix & 255) | ((iy & 255) << 8) | ((iz & 255) << 16) | ((iw & 255) << 24);
    }
    static int packByte(float v) {
        if (Float.isNaN(v)) return 0;
        return (int) (Math.max(-1f, Math.min(1f, v)) * 127f);
    }
    static void unpack(int packed, float[] out) {
        out[0] = (byte) (packed & 255) / 127f;
        out[1] = (byte) ((packed >> 8) & 255) / 127f;
        out[2] = (byte) ((packed >> 16) & 255) / 127f;
        out[3] = (byte) ((packed >> 24) & 255) / 127f;
    }
    // computeFaceNormalManual: diagonal cross, JOML normalize (NaN on zero!)
    static void faceNormal(float[] n, float[] p) {
        float dx0 = p[8]-p[0], dy0 = p[9]-p[1], dz0 = p[10]-p[2];
        float dx1 = p[9]-p[3], dy1 = p[10]-p[4], dz1 = p[11]-p[5];
        float nx = dy0*dz1 - dz0*dy1, ny = dz0*dx1 - dx0*dz1, nz = dx0*dy1 - dy0*dx1;
        float len = (float) Math.sqrt(nx*nx+ny*ny+nz*nz);
        if (len > 1e-20f) { n[0]=nx/len; n[1]=ny/len; n[2]=nz/len; } else { n[0]=Float.NaN; n[1]=Float.NaN; n[2]=Float.NaN; }
    }
    // computeTangent tri (v0,v1,v2), rsqrt(0)->1 (per Iris rsqrt guard)
    static float rsqrt(float v) { return v == 0f ? 1f : (float)(1.0/Math.sqrt(v)); }
    static int tangent(float nx, float ny, float nz, float[] p, float[] uv) {
        float e1x=p[4]-p[0], e1y=p[5]-p[1], e1z=p[6]-p[2];
        float e2x=p[8]-p[0], e2y=p[9]-p[1], e2z=p[10]-p[2];
        float du1=uv[2]-uv[0], dv1=uv[3]-uv[1], du2=uv[4]-uv[0], dv2=uv[5]-uv[1];
        float fd = du1*dv2 - du2*dv1; float f = fd == 0f ? 1f : 1f/fd;
        float tx = f*(dv2*e1x - dv1*e2x), ty = f*(dv2*e1y - dv1*e2y), tz = f*(dv2*e1z - dv1*e2z);
        float tc = rsqrt(tx*tx+ty*ty+tz*tz);
        tx*=tc; ty*=tc; tz*=tc;
        if (tx==0f && ty==0f && tz==0f) return -1;
        float bx = f*(-du2*e1x + du1*e2x), by = f*(-du2*e1y + du1*e2y), bz = f*(-du2*e1z + du1*e2z);
        float bc = rsqrt(bx*bx+by*by+bz*bz);
        bx*=bc; by*=bc; bz*=bc;
        float pbx = ty*nz - tz*ny, pby = tz*nx - tx*nz, pbz = tx*ny - ty*nx;
        float dot = bx*pbx + by*pby + bz*pbz;
        return pack(tx, ty, tz, dot < 0 ? -1f : 1f);
    }
    public static void main(String[] a) {
        UVLayout skin = new UVLayout(new UVQuad(24,21,27,26), new UVQuad(16,21,20,26),
                new UVQuad(20,17,24,21), new UVQuad(20,25,24,27), new UVQuad(20,21,24,26));
        UVLayout overlay = new UVLayout(null, new UVQuad(17,37,20,42), new UVQuad(20,34,24,37),
                new UVQuad(20,42,24,45), new UVQuad(20,37,24,42));
        sim("SKIN", new WildfireModelRenderer.BreastModelBox(64,64,-4F,0F,0F,4,5,3,0.0F,skin));
        sim("OVERLAY", new WildfireModelRenderer.OverlayModelBox(64,64,-4F,0F,0F,4,5,3,0.0F,overlay));
    }
    static void sim(String tag, WildfireModelRenderer.ModelBox box) {
        System.out.println("=== " + tag + " flat quads (upstream emission) ===");
        int garbage = 0;
        for (WildfireModelRenderer.TexturedQuad q : box.quads) {
            if (q == null || (q.uvs[0]==0f && q.uvs[1]==0f && q.uvs[2]==0f && q.uvs[3]==0f)) continue;
            garbage += dump(q.vertexPositions);
        }
        System.out.println(tag + " flat garbage=" + garbage);
        RoundBreastMesh mesh = RoundBreastMesh.of(box, 0.15821917F, true);
        System.out.println("=== " + tag + " mesh quads (ours, full morph) ===");
        garbage = 0;
        float[] d = mesh.data;
        for (int quad = 0; quad < mesh.quadCount; quad++) {
            float[] p = new float[12], uv = new float[8];
            for (int v = 0; v < 4; v++) {
                int o = quad*32+v*8;
                p[v*3]=d[o]*16; p[v*3+1]=d[o+1]*16; p[v*3+2]=d[o+2]*16;
                uv[v*2]=d[o+6]; uv[v*2+1]=d[o+7];
            }
            garbage += dumpMesh(quad, p, uv, d, quad*32);
        }
        System.out.println(tag + " mesh garbage=" + garbage);
    }
    static int dump(WildfireModelRenderer.PositionTextureVertex[] vs) {
        float[] p = new float[12], uv = new float[8];
        for (int v = 0; v < 4; v++) {
            p[v*3]=vs[v].x(); p[v*3+1]=vs[v].y(); p[v*3+2]=vs[v].z();
            uv[v*2]=vs[v].u(); uv[v*2+1]=vs[v].v();
        }
        return dumpCommon(p, uv, "  flat");
    }
    static int dumpMesh(int quad, float[] p, float[] uv, float[] d, int base) {
        int bad = dumpCommon(p, uv, "  mesh q" + quad);
        // also report original smooth normal of v0 for comparison
        return bad;
    }
    static int dumpCommon(float[] p, float[] uv, String label) {
        float[] n = new float[3];
        faceNormal(n, p);
        int t = tangent(n[0], n[1], n[2], p, uv);
        float[] tU = new float[4];
        unpack(t, tU);
        float midU = (uv[0]+uv[2]+uv[4]+uv[6])/4f, midV = (uv[1]+uv[3]+uv[5]+uv[7])/4f;
        boolean bad = Float.isNaN(n[0]) || t == -1 || Float.isNaN(tU[0]) || Float.isNaN(tU[1]) || Float.isNaN(tU[2]);
        if (bad) System.out.printf("%s BAD n=(%s,%s,%s) tangentPacked=%08x t=(%s,%s,%s,%s)%n", label,
                n[0], n[1], n[2], t, tU[0], tU[1], tU[2], tU[3]);
        return bad ? 1 : 0;
    }
}
