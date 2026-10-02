package com.example.blockexport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monta a malha do modo AMS: cada face e dividida numa grade (detail x detail), a cor de cada celula vem
 * da textura (media dos pixels opacos naquela area, ja com o tint do bioma) e vira o filamento mais proximo.
 * Saida: OBJ com 1 material por filamento (Kd = cor do filamento, sem textura) + MTL.
 *
 * Sem classes do Minecraft, pra poder ser testada fora do jogo.
 */
final class AmsMesh {

    /** Face: posicoes (3 ou 4 vertices) e UV local 0..1 (v pra cima, igual ao PrintFace do ModelExporter). tex = indice em texs. */
    record Face(float[] p, float[] t, int tex) {}

    /** Textura em ABGR (formato do NativeImage.getPixelRGBA). */
    record Tex(int[] abgr, int w, int h) {}

    record Used(String name, int rgb, int faces, double area) {}

    record Result(String obj, String mtl, List<Used> used, int detailUsed, int faces, int cellsDropped) {}

    private static final long CELL_BUDGET = 6_000_000L;
    private static final int ALPHA_MIN = 128;

    private AmsMesh() {}

    static Result build(List<Face> faces, List<Tex> texs, List<AmsPalette.Filament> palette,
                        int maxColors, int detail, String mtlFile) {
        int n = Math.max(1, Math.min(16, detail));
        if ((long) faces.size() * n * n > CELL_BUDGET) n = Math.max(1, (int) Math.sqrt((double) CELL_BUDGET / Math.max(1, faces.size())));

        List<double[]> palLab = new ArrayList<>();
        for (AmsPalette.Filament f : palette) palLab.add(AmsPalette.lab(f.rgb()));

        // --- passo A: quanto de cada cor (quantizada) aparece, pesado pela area ---
        double[] hist = new double[32768];
        for (Face f : faces) {
            int[] cells = cellKeys(f, texs.get(f.tex()), n);
            double w = area(f.p()) / cells.length;
            for (int key : cells) if (key >= 0) hist[key] += w;
        }
        List<double[]> srcLab = new ArrayList<>();
        List<Double> srcW = new ArrayList<>();
        for (int k = 0; k < hist.length; k++) {
            if (hist[k] <= 0) continue;
            srcLab.add(AmsPalette.lab(keyColor(k)));
            srcW.add(hist[k]);
        }
        double[] weights = new double[srcW.size()];
        for (int i = 0; i < weights.length; i++) weights[i] = srcW.get(i);
        List<Integer> chosen = AmsPalette.choose(srcLab, weights, palLab, maxColors);
        List<double[]> chosenLab = new ArrayList<>();
        for (int idx : chosen) chosenLab.add(palLab.get(idx));

        int[] keyToChosen = new int[32768];
        Arrays.fill(keyToChosen, -1);

        // --- passo B: malha ---
        Map<Long, Integer> vmap = new HashMap<>();
        StringBuilder vs = new StringBuilder();
        StringBuilder[] fs = new StringBuilder[chosen.size()];
        for (int i = 0; i < fs.length; i++) fs[i] = new StringBuilder();
        int[] faceCount = new int[chosen.size()];
        double[] areaSum = new double[chosen.size()];
        int[] vc = {0};
        int dropped = 0, totalFaces = 0;

        for (Face f : faces) {
            Tex tx = texs.get(f.tex());
            int[] cells = cellKeys(f, tx, n);
            int vcnt = f.p().length / 3;
            double cellArea = area(f.p()) / cells.length;

            int[] fil = new int[cells.length];
            for (int i = 0; i < cells.length; i++) {
                int key = cells[i];
                if (key < 0) { fil[i] = -1; dropped++; continue; }
                int c = keyToChosen[key];
                if (c < 0) { c = AmsPalette.nearest(AmsPalette.lab(keyColor(key)), chosenLab); keyToChosen[key] = c; }
                fil[i] = c;
            }

            if (vcnt != 4) {                       // triangulo: uma celula so
                if (fil[0] < 0) continue;
                int[] ids = new int[vcnt];
                for (int k = 0; k < vcnt; k++) ids[k] = vertex(vmap, vs, vc, f.p()[k * 3], f.p()[k * 3 + 1], f.p()[k * 3 + 2]);
                if (emit(fs[fil[0]], ids)) { faceCount[fil[0]]++; areaSum[fil[0]] += cellArea; totalFaces++; }
                continue;
            }

            for (int b = 0; b < n; b++) {
                int a = 0;
                while (a < n) {
                    int c = fil[b * n + a];
                    if (c < 0) { a++; continue; }
                    int a2 = a + 1;
                    while (a2 < n && fil[b * n + a2] == c) a2++;          // junta celulas vizinhas da mesma cor
                    float s0 = (float) a / n, s1 = (float) a2 / n, t0 = (float) b / n, t1 = (float) (b + 1) / n;
                    int[] ids = {
                        corner(vmap, vs, vc, f.p(), s0, t0), corner(vmap, vs, vc, f.p(), s1, t0),
                        corner(vmap, vs, vc, f.p(), s1, t1), corner(vmap, vs, vc, f.p(), s0, t1)};
                    if (emit(fs[c], ids)) { faceCount[c]++; areaSum[c] += cellArea * (a2 - a); totalFaces++; }
                    a = a2;
                }
            }
        }

        // --- texto ---
        StringBuilder obj = new StringBuilder("# Block Export - modo AMS (1 bloco = 1 unidade, Y para cima)\n");
        obj.append("mtllib ").append(mtlFile).append("\no minecraft_build\n").append(vs);
        StringBuilder mtl = new StringBuilder("# Block Export - cores dos filamentos\n");
        List<Used> used = new ArrayList<>();
        for (int i = 0; i < fs.length; i++) {
            if (faceCount[i] == 0) continue;
            AmsPalette.Filament fil = palette.get(chosen.get(i));
            String name = matName(fil);
            obj.append("usemtl ").append(name).append('\n').append(fs[i]);
            mtl.append("newmtl ").append(name).append('\n')
               .append(String.format(Locale.ROOT, "Kd %.4f %.4f %.4f\n", ((fil.rgb() >> 16) & 255) / 255.0,
                   ((fil.rgb() >> 8) & 255) / 255.0, (fil.rgb() & 255) / 255.0))
               .append("Ka 0 0 0\nKs 0 0 0\nd 1\nillum 1\n\n");
            used.add(new Used(fil.name(), fil.rgb(), faceCount[i], areaSum[i]));
        }
        used.sort((x, y) -> Double.compare(y.area(), x.area()));
        return new Result(obj.toString(), mtl.toString(), used, n, totalFaces, dropped);
    }

    static String matName(AmsPalette.Filament f) {
        return (f.name().replaceAll("[^A-Za-z0-9_.-]", "_") + "_" + String.format("%06X", f.rgb()));
    }

    // ------------------------------------------------------------------ celulas

    /**
     * Cor (chave de 15 bits) de cada celula da face, em ordem de linha; -1 = celula totalmente transparente.
     * Triangulos viram uma celula so.
     */
    private static int[] cellKeys(Face f, Tex tx, int n) {
        int vcnt = f.p().length / 3;
        if (vcnt != 4) {
            float[] u = new float[vcnt], v = new float[vcnt];
            for (int k = 0; k < vcnt; k++) { u[k] = f.t()[k * 2]; v[k] = f.t()[k * 2 + 1]; }
            return new int[]{sample(tx, u, v)};
        }
        int[] out = new int[n * n];
        float[] u = new float[4], v = new float[4];
        for (int b = 0; b < n; b++) {
            for (int a = 0; a < n; a++) {
                float s0 = (float) a / n, s1 = (float) (a + 1) / n, t0 = (float) b / n, t1 = (float) (b + 1) / n;
                float[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
                for (int k = 0; k < 4; k++) {
                    float[] uv = bilerp(f.t(), 2, st[k][0], st[k][1]);
                    u[k] = uv[0]; v[k] = uv[1];
                }
                out[b * n + a] = sample(tx, u, v);
            }
        }
        return out;
    }

    /** Media dos pixels opacos dentro da caixa dos cantos UV dados. Retorna chave 15 bits ou -1. */
    private static int sample(Tex tx, float[] u, float[] v) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int k = 0; k < u.length; k++) {
            float x = clamp01(u[k]) * tx.w(), y = (1f - clamp01(v[k])) * tx.h();
            x0 = Math.min(x0, x); x1 = Math.max(x1, x);
            y0 = Math.min(y0, y); y1 = Math.max(y1, y);
        }
        int ix0 = clampi((int) Math.floor(x0 + 1e-4f), 0, tx.w() - 1);
        int ix1 = clampi((int) Math.ceil(x1 - 1e-4f) - 1, 0, tx.w() - 1);
        int iy0 = clampi((int) Math.floor(y0 + 1e-4f), 0, tx.h() - 1);
        int iy1 = clampi((int) Math.ceil(y1 - 1e-4f) - 1, 0, tx.h() - 1);
        if (ix1 < ix0) ix1 = ix0;
        if (iy1 < iy0) iy1 = iy0;

        long r = 0, g = 0, b = 0;
        int cnt = 0;
        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                int px = tx.abgr()[y * tx.w() + x];
                if ((px >>> 24) < ALPHA_MIN) continue;
                r += px & 255; g += (px >> 8) & 255; b += (px >> 16) & 255;
                cnt++;
            }
        }
        if (cnt == 0) return -1;
        return key((int) (r / cnt), (int) (g / cnt), (int) (b / cnt));
    }

    private static int key(int r, int g, int b) { return ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3); }

    private static int keyColor(int k) {
        int r5 = (k >> 10) & 31, g5 = (k >> 5) & 31, b5 = k & 31;
        return (((r5 << 3) | (r5 >> 2)) << 16) | (((g5 << 3) | (g5 >> 2)) << 8) | ((b5 << 3) | (b5 >> 2));
    }

    // ------------------------------------------------------------------ geometria

    /** Interpolacao bilinear sobre 4 cantos (ordem p0,p1,p2,p3 do quad), dim valores por canto. */
    private static float[] bilerp(float[] c, int dim, float s, float t) {
        float[] r = new float[dim];
        for (int i = 0; i < dim; i++) {
            float a = c[i] + (c[dim + i] - c[i]) * s;                          // p0 -> p1
            float b = c[3 * dim + i] + (c[2 * dim + i] - c[3 * dim + i]) * s;  // p3 -> p2
            r[i] = a + (b - a) * t;
        }
        return r;
    }

    private static int corner(Map<Long, Integer> vmap, StringBuilder vs, int[] vc, float[] p, float s, float t) {
        float[] q = bilerp(p, 3, s, t);
        return vertex(vmap, vs, vc, q[0], q[1], q[2]);
    }

    private static int vertex(Map<Long, Integer> vmap, StringBuilder vs, int[] vc, float x, float y, float z) {
        long kx = Math.round(x * 1000) + 0x10000L, ky = Math.round(y * 1000) + 0x10000L, kz = Math.round(z * 1000) + 0x10000L;
        long key = (kx << 42) | (ky << 21) | kz;
        Integer id = vmap.get(key);
        if (id == null) {
            id = ++vc[0];
            vmap.put(key, id);
            vs.append("v ").append(fmt(x)).append(' ').append(fmt(y)).append(' ').append(fmt(z)).append('\n');
        }
        return id;
    }

    /** Escreve a face se nao for degenerada (menos de 3 vertices distintos). */
    private static boolean emit(StringBuilder fs, int[] ids) {
        int distinct = 0;
        for (int a = 0; a < ids.length; a++) {
            boolean dup = false;
            for (int b = 0; b < a; b++) if (ids[a] == ids[b]) { dup = true; break; }
            if (!dup) distinct++;
        }
        if (distinct < 3) return false;
        fs.append('f');
        for (int id : ids) fs.append(' ').append(id);
        fs.append('\n');
        return true;
    }

    private static double area(float[] p) {
        int n = p.length / 3;
        double ax, ay, az, bx, by, bz;
        if (n >= 4) {
            ax = p[6] - p[0]; ay = p[7] - p[1]; az = p[8] - p[2];
            bx = p[9] - p[3]; by = p[10] - p[4]; bz = p[11] - p[5];
        } else {
            ax = p[3] - p[0]; ay = p[4] - p[1]; az = p[5] - p[2];
            bx = p[6] - p[0]; by = p[7] - p[1]; bz = p[8] - p[2];
        }
        double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) / 2;
    }

    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }

    private static int clampi(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String fmt(float v) { return String.format(Locale.ROOT, "%.5f", v); }
}
