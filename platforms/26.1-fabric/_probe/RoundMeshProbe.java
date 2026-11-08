import io.github.e33epus.fgmplus.render.RoundBreastMesh;

/**
 * Offline math probe for RoundBreastMesh.project — runs against the built
 * classes, no Minecraft involved. Exit code 0 = all assertions passed.
 */
public class RoundMeshProbe {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <- " + detail));
        if(!ok) failures++;
    }

    static double superSum(float[] p, float hx, float hy, float hz, double n) {
        return Math.pow(Math.abs(p[0] / hx), n) + Math.pow(Math.abs(p[1] / hy), n) + Math.pow(Math.abs(p[2] / hz), n);
    }

    public static void main(String[] args) {
        float hx = 2f, hy = 2.5f, hz = 1.5f; //4x5x3 box half extents in px

        //1. axial face centers never move (the breast keeps touching the torso)
        for(float r : new float[]{0.25f, 0.5f, 1f}) {
            float[] p = RoundBreastMesh.probeProject(r, hx, 0f, 0f, hx, hy, hz);
            check("axial-center x@r=" + r, Math.abs(p[0] - hx) < 1e-4 && Math.abs(p[1]) < 1e-4 && Math.abs(p[2]) < 1e-4,
                    p[0] + "," + p[1] + "," + p[2]);
            float[] q = RoundBreastMesh.probeProject(r, 0f, 0f, -hz, hx, hy, hz);
            check("axial-center -z@r=" + r, Math.abs(q[2] + hz) < 1e-4, q[0] + "," + q[1] + "," + q[2]);
        }

        //2. projected points satisfy the superellipsoid equation sum|x/h|^n = 1
        for(float r : new float[]{0.5f, 1f}) {
            double n = 2.0 / r;
            float[][] corners = {
                    {hx, hy, hz}, {hx, 0f, hz}, {hx * 0.5f, hy, hz}, {hx, hy * -0.5f, -hz}
            };
            for(float[] c : corners) {
                float[] p = RoundBreastMesh.probeProject(r, c[0], c[1], c[2], hx, hy, hz);
                double s = superSum(p, hx, hy, hz, n);
                check("surface-eq r=" + r + " in=" + c[0] + "," + c[1] + "," + c[2], Math.abs(s - 1.0) < 1e-3, "sum=" + s);
            }
        }

        //3. sphere case r=1: corner projects onto the inscribed sphere, radius = min half extent? no —
        //   the superellipsoid with n=2 in normalized space: |p/h| = 1 exactly (checked above);
        //   additionally the world radius along the (1,1,1) direction must be < corner distance
        {
            float[] p = RoundBreastMesh.probeProject(1f, hx, hy, hz, hx, hy, hz);
            float d2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2];
            float corner2 = hx * hx + hy * hy + hz * hz;
            check("sphere-corner-shrinks", d2 < corner2 * 0.99f, "d2=" + d2 + " corner2=" + corner2);
        }

        //4. normal is perpendicular to the (stretched) surface. For n=2 the surface is
        //   the half-extent-scaled sphere: F = (x/hx)^2+..., gradient = 2(x/hx^2, ...) —
        //   the normal must be parallel to that, and unit length
        {
            float[] p = RoundBreastMesh.probeProject(1f, hx, hy, hz, hx, hy, hz);
            double gx = p[0] / (hx * hx), gy = p[1] / (hy * hy), gz = p[2] / (hz * hz);
            double gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
            double dot = gx * p[3] + gy * p[4] + gz * p[5];
            double pl = Math.sqrt(p[3] * p[3] + p[4] * p[4] + p[5] * p[5]);
            check("sphere-normal-radial", Math.abs(dot - gl * pl) < 1e-4, "dot=" + dot + " gl*pl=" + gl * pl);
            check("normal-unit", Math.abs(pl - 1.0) < 1e-4, "|n|=" + pl);
        }

        //5. envelope: no projected vertex may leave the original box (the anti-clip
        //   guarantee — a rounded breast never pokes further out than the flat one).
        //   Sample the full face grid of the +x face
        {
            boolean inside = true;
            for(float r : new float[]{0.1f, 0.25f, 0.5f, 0.75f, 1f}) {
                for(int i = 0; i <= 8; i++) for(int j = 0; j <= 8; j++) {
                    float[] p = RoundBreastMesh.probeProject(r,
                            hx, -hy + (2 * hy) * i / 8f, -hz + (2 * hz) * j / 8f, hx, hy, hz);
                    if(Math.abs(p[0]) > hx + 1e-4 || Math.abs(p[1]) > hy + 1e-4 || Math.abs(p[2]) > hz + 1e-4) {
                        inside = false;
                    }
                }
            }
            check("envelope-respected", inside, "a projected vertex left the original box");
        }

        //6. monotonic rounding: corner pull-in grows with roundness
        {
            float prev = Float.MAX_VALUE;
            for(float r : new float[]{0.1f, 0.3f, 0.6f, 1f}) {
                float[] p = RoundBreastMesh.probeProject(r, hx, hy, hz, hx, hy, hz);
                float d2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2];
                check("monotonic@r=" + r, d2 < prev, "d2=" + d2 + " prev=" + prev);
                prev = d2;
            }
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
