import com.wildfire.api.uvs.UVLayout;
import com.wildfire.api.uvs.UVQuad;
import com.wildfire.client.render.WildfireModelRenderer;
import io.github.e33epus.fgmplus.render.RoundBreastMesh;

/**
 * Offline harness for RoundBreastMesh.of against a REAL FGM BreastModelBox —
 * the exact 4x5x3 box GenderLayer builds, plus an overlay-style layout with
 * degenerate (all-zero) faces like the dev player's config has. No Minecraft.
 */
public class RoundMeshHarness {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <- " + detail));
        if(!ok) failures++;
    }

    public static void main(String[] args) {
        //skin layout: the 5-face layout FGM uses (all faces mapped)
        UVLayout skin = new UVLayout(
                new UVQuad(24, 21, 27, 26),
                new UVQuad(16, 21, 20, 26),
                new UVQuad(20, 17, 24, 21),
                new UVQuad(20, 25, 24, 27),
                new UVQuad(20, 21, 24, 26));
        //exactly the box from GenderLayer.resizeBox: left breast, 4x5x3 at (-4,0,0)
        WildfireModelRenderer.ModelBox box = new WildfireModelRenderer.BreastModelBox(64, 64, -4F, 0F, 0F, 4, 5, 3, 0.0F, skin);

        RoundBreastMesh mesh = RoundBreastMesh.of(box, 0.85F, true);
        System.out.println("quadCount=" + mesh.quadCount + " floats=" + mesh.data.length);
        check("nonempty-mesh", mesh.quadCount > 0 && mesh.data.length == mesh.quadCount * 32,
                "quads=" + mesh.quadCount + " floats=" + mesh.data.length);
        //4x5x3 faces subdivided by GEOMETRIC edge length (not UV size): E(5x3)=15,
        //W(5x3)=15, D(4x3)=12, U(4x3)=12, N(4x5)=20 -> 74 quads
        check("quad-count-matches-faces", mesh.quadCount == 74, "quads=" + mesh.quadCount + " expected 74");
        check("cache-stable", RoundBreastMesh.of(box, 0.85F, true) == mesh, "cache returned a fresh mesh");
        check("cache-invalidates", RoundBreastMesh.of(box, 0.5F, true) != mesh, "roundness change kept stale mesh");

        //every vertex inside the source box envelope (anti-clip), every normal unit
        boolean inside = true, unit = true;
        for(int o = 0; o < mesh.data.length; o += 8) {
            float x = mesh.data[o], y = mesh.data[o + 1], z = mesh.data[o + 2];
            //box: x -4..0, y 0..5, z 0..3 px -> /16 world units
            if(x < -4f / 16 - 1e-4 || x > 1.3f / 16 + 1e-4 || y < -1e-4 || y > 5f / 16 + 1e-4 || z < -1e-4 || z > 3f / 16 + 1e-4) {
                inside = false;
                System.out.println("  outside vertex at " + o + ": " + x + "," + y + "," + z);
            }
            float nx = mesh.data[o + 3], ny = mesh.data[o + 4], nz = mesh.data[o + 5];
            if(Math.abs(nx * nx + ny * ny + nz * nz - 1f) > 1e-3) unit = false;
            float u = mesh.data[o + 6], v = mesh.data[o + 7];
            if(u < 0 || v < 0 || u > 1 || v > 1) { unit = false; System.out.println("  uv out of atlas: " + u + "," + v); }
        }
        check("envelope", inside, "vertex escaped the source box");
        check("normals-and-uvs", unit, "non-unit normal or UV outside [0,1]");

        //curvature actually applied: the max normalized EUCLIDEAN radius of a super-
        //ellipsoid with n>2 sits at the diagonal: sqrt(3^(1-2/n)); for r=0.85,
        //n=2/0.85 -> sqrt(3^0.15) = 1.0860. A flat box would give sqrt(3)=1.732,
        //an inscribed sphere 1.0 — matching theory to 4 decimals proves the morph
        float maxDist = 0f;
        for(int o = 0; o < mesh.data.length; o += 8) {
            float x = mesh.data[o] * 16 + 2, y = mesh.data[o + 1] * 16 - 2.5f, z = mesh.data[o + 2] * 16 - 1.5f;
            if(x >= 0) continue; //bridged inner half is intentionally NOT the pure surface
            maxDist = Math.max(maxDist, (float) Math.sqrt(x * x / 4f + y * y / 6.25f + z * z / 2.25f));
        }
        float theory = (float) Math.sqrt(Math.pow(3, 1 - 2 * 0.85 / 2));
        check("curvature-applied", Math.abs(maxDist - theory) < 1e-3, "max=" + maxDist + " theory=" + theory);

        //cleavage bridge: the bridged mesh's inner vertices must cross the torso
        //centerline plane (world x = 0), while the plain (bridge-off) mesh must not
        RoundBreastMesh plain = RoundBreastMesh.of(box, 0.85F, false);
        float bridgedMaxX = Float.NEGATIVE_INFINITY, plainMaxX = Float.NEGATIVE_INFINITY;
        for(int o = 0; o < mesh.data.length; o += 8) bridgedMaxX = Math.max(bridgedMaxX, mesh.data[o]);
        for(int o = 0; o < plain.data.length; o += 8) plainMaxX = Math.max(plainMaxX, plain.data[o]);
        check("cleavage-bridge", bridgedMaxX > 0.5f / 16 && bridgedMaxX <= 1.3f / 16 && plainMaxX <= 1e-4,
                "bridged max x=" + (bridgedMaxX * 16) + "px plain max x=" + (plainMaxX * 16) + "px");

        //overlay-style layout: two degenerate faces must be skipped, not crash or emit
        UVLayout overlay = new UVLayout(
                null,
                new UVQuad(17, 37, 20, 42),
                new UVQuad(20, 34, 24, 37),
                new UVQuad(20, 42, 24, 45),
                new UVQuad(20, 37, 24, 42));
        WildfireModelRenderer.ModelBox wear = new WildfireModelRenderer.OverlayModelBox(64, 64, -4F, 0F, 0F, 4, 5, 3, 0.0F, overlay);
        RoundBreastMesh wearMesh = RoundBreastMesh.of(wear, 0.85F, true);
        //null east + zero-uvs east quad: initQuads leaves quads[i] null for null UVQuad
        check("overlay-mesh-nonempty", wearMesh.quadCount > 0, "quads=" + wearMesh.quadCount);

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
