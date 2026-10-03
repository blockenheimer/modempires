package com.example.blockexport;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometria "por pixel" do exportador. Classe pura (sem dependencia do Minecraft) pra poder ser testada isolada.
 *
 *  - {@link Grid}: divide um quad na grade de texels da textura (1 celula = 1 texel).
 *  - {@link #solidPlane}: "solidify" quadrado. Pega um plano fino (flor, escada, trilho...) e cria, so pros texels
 *    opacos, uma caixa por pixel: mantem a silhueta do pixel art, so que com espessura.
 */
final class PixelTools {

    /** Alpha (0..255) do texel (x, y) da textura. */
    interface Alpha { int alpha(int x, int y); }

    /** Um quad pronto: p = 4 vertices (12 floats), t = 4 UVs locais (u, v pra CIMA, 0..1), mesma convencao do exportador. */
    record Cell(float[] p, float[] t) {}

    private PixelTools() {}

    // ------------------------------------------------------------------ grade

    /**
     * Grade de um quad (vertices 0,1,2,3 em volta). Coordenadas (s,t) em 0..1: v0=(0,0) v1=(1,0) v2=(1,1) v3=(0,1).
     * O numero de celulas e o tamanho do quad medido em texels da textura (UV * tamanho da imagem).
     */
    static final class Grid {
        final int w, h, ns, nt;
        final float[] p;          // 12 floats
        final float[] uv;         // 8 floats, em texels, V pra BAIXO (como a imagem)
        final boolean collapsed;  // UV colapsada (laterais): sem orientacao de textura

        Grid(float[] p, float[] t, int w, int h, int maxDiv) {
            if (p.length < 12 || t.length < 8) throw new IllegalArgumentException("quad precisa de 4 vertices");
            this.p = p;
            this.w = w;
            this.h = h;
            uv = new float[8];
            for (int k = 0; k < 4; k++) {
                uv[k * 2] = clamp01(t[k * 2]) * w;
                uv[k * 2 + 1] = (1f - clamp01(t[k * 2 + 1])) * h;
            }
            float ls = dist(0, 1), lt = dist(0, 3);
            ns = Math.max(1, Math.min(maxDiv, Math.round(ls)));
            nt = Math.max(1, Math.min(maxDiv, Math.round(lt)));
            collapsed = ls < 1e-3f && lt < 1e-3f;
        }

        private float dist(int a, int b) {
            float dx = uv[b * 2] - uv[a * 2], dy = uv[b * 2 + 1] - uv[a * 2 + 1];
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        void pos(float s, float t, float[] out, int o) {
            float w0 = (1 - s) * (1 - t), w1 = s * (1 - t), w2 = s * t, w3 = (1 - s) * t;
            for (int a = 0; a < 3; a++) out[o + a] = w0 * p[a] + w1 * p[3 + a] + w2 * p[6 + a] + w3 * p[9 + a];
        }

        /** UV em texels (u, v pra baixo). */
        void tex(float s, float t, float[] out, int o) {
            float w0 = (1 - s) * (1 - t), w1 = s * (1 - t), w2 = s * t, w3 = (1 - s) * t;
            for (int a = 0; a < 2; a++) out[o + a] = w0 * uv[a] + w1 * uv[2 + a] + w2 * uv[4 + a] + w3 * uv[6 + a];
        }

        /** Texel (coluna, linha) da imagem que a celula (i, j) cobre: o do centro da celula. */
        int[] texel(int i, int j) {
            float[] c = new float[2];
            tex((i + 0.5f) / ns, (j + 0.5f) / nt, c, 0);
            int x = Math.max(0, Math.min(w - 1, (int) Math.floor(c[0])));
            int y = Math.max(0, Math.min(h - 1, (int) Math.floor(c[1])));
            return new int[]{x, y};
        }
    }

    // ------------------------------------------------------------------ solidify

    /**
     * Plano fino -> relevo quadrado por pixel.
     *
     * Cada texel com alpha > 0 vira uma caixa: face da frente (no plano + n*front), face de tras (plano - n*back) e
     * paredes so nos lados que dao pra fora da silhueta (vizinho transparente ou borda). O resultado e uma malha
     * fechada e com as faces viradas pra fora. Texels totalmente transparentes nao geram nada.
     *
     * @return lista vazia se nao ha nenhum texel opaco; {@code null} se TODOS os texels da grade sao opacos
     *         (nesse caso o chamador pode usar uma laje simples, bem mais leve).
     */
    static List<Cell> solidPlane(float[] p, float[] t, int w, int h, Alpha alpha, float[] n,
                                 float front, float back, int maxDiv) {
        Grid g = new Grid(p, t, w, h, maxDiv);
        int ns = g.ns, nt = g.nt;
        boolean[][] op = new boolean[ns][nt];
        int[][][] tx = new int[ns][nt][];
        int count = 0;
        for (int i = 0; i < ns; i++) {
            for (int j = 0; j < nt; j++) {
                tx[i][j] = g.texel(i, j);
                op[i][j] = alpha.alpha(tx[i][j][0], tx[i][j][1]) > 0;
                if (op[i][j]) count++;
            }
        }
        if (count == 0) return new ArrayList<>();
        if (count == ns * nt) return null;

        List<Cell> out = new ArrayList<>();
        float[][] pl = new float[4][3];          // cantos no plano
        float[][] tc = new float[4][2];          // UV local (u, v pra cima) de cada canto
        float[] tmp = new float[3];
        float[] tt = new float[2];

        for (int i = 0; i < ns; i++) {
            for (int j = 0; j < nt; j++) {
                if (!op[i][j]) continue;
                for (int k = 0; k < 4; k++) {
                    int ci = (k == 1 || k == 2) ? 1 : 0, cj = k >= 2 ? 1 : 0;
                    float s = (i + ci) / (float) ns, tv = (j + cj) / (float) nt;
                    g.pos(s, tv, tmp, 0);
                    System.arraycopy(tmp, 0, pl[k], 0, 3);
                    g.tex(s, tv, tt, 0);
                    tc[k][0] = tt[0] / w;
                    tc[k][1] = 1f - tt[1] / h;
                }

                // frente
                float[] fp = new float[12], ft = new float[8];
                for (int k = 0; k < 4; k++) {
                    for (int a = 0; a < 3; a++) fp[k * 3 + a] = pl[k][a] + n[a] * front;
                    ft[k * 2] = tc[k][0];
                    ft[k * 2 + 1] = tc[k][1];
                }
                out.add(new Cell(fp, ft));

                // tras (ordem invertida: olha pro lado oposto)
                float[] bp = new float[12], bt = new float[8];
                for (int k = 0; k < 4; k++) {
                    int s = 3 - k;
                    for (int a = 0; a < 3; a++) bp[k * 3 + a] = pl[s][a] - n[a] * back;
                    bt[k * 2] = tc[s][0];
                    bt[k * 2 + 1] = tc[s][1];
                }
                out.add(new Cell(bp, bt));

                // paredes: lado (i-1) usa cantos 0-3, (i+1) cantos 1-2, (j-1) cantos 0-1, (j+1) cantos 2-3
                float u0 = (tx[i][j][0] + 0.5f) / w, v0 = 1f - (tx[i][j][1] + 0.5f) / h;
                if (i == 0 || !op[i - 1][j]) out.add(wall(pl, n, front, back, 0, 3, u0, v0));
                if (i == ns - 1 || !op[i + 1][j]) out.add(wall(pl, n, front, back, 1, 2, u0, v0));
                if (j == 0 || !op[i][j - 1]) out.add(wall(pl, n, front, back, 0, 1, u0, v0));
                if (j == nt - 1 || !op[i][j + 1]) out.add(wall(pl, n, front, back, 3, 2, u0, v0));
            }
        }
        return out;
    }

    /** Parede entre os cantos a e b, da frente ate o fundo, virada pra fora da celula. UV colapsada no texel. */
    private static Cell wall(float[][] pl, float[] n, float front, float back, int a, int b, float u, float v) {
        float[] sp = new float[12];
        for (int c = 0; c < 3; c++) {
            sp[c] = pl[a][c] + n[c] * front;
            sp[3 + c] = pl[a][c] - n[c] * back;
            sp[6 + c] = pl[b][c] - n[c] * back;
            sp[9 + c] = pl[b][c] + n[c] * front;
        }
        // centro da celula (entre frente e fundo)
        float[] c0 = new float[3];
        for (int k = 0; k < 4; k++) for (int c = 0; c < 3; c++) c0[c] += pl[k][c] / 4f;
        for (int c = 0; c < 3; c++) c0[c] += n[c] * (front - back) / 2f;

        float[] sn = normal(sp);
        float mx = (sp[0] + sp[3] + sp[6] + sp[9]) / 4f - c0[0];
        float my = (sp[1] + sp[4] + sp[7] + sp[10]) / 4f - c0[1];
        float mz = (sp[2] + sp[5] + sp[8] + sp[11]) / 4f - c0[2];
        if (sn[0] * mx + sn[1] * my + sn[2] * mz < 0) {
            for (int c = 0; c < 3; c++) {
                float x = sp[c]; sp[c] = sp[9 + c]; sp[9 + c] = x;
                x = sp[3 + c]; sp[3 + c] = sp[6 + c]; sp[6 + c] = x;
            }
        }
        float[] st = {u, v, u, v, u, v, u, v};
        return new Cell(sp, st);
    }

    /** Normal (nao normalizada) de um quad pelas diagonais, mesma convencao de QuadCollector.faceNormal. */
    static float[] normal(float[] p) {
        float e1x = p[6] - p[0], e1y = p[7] - p[1], e1z = p[8] - p[2];
        float e2x = p[9] - p[3], e2y = p[10] - p[4], e2z = p[11] - p[5];
        return new float[]{e1y * e2z - e1z * e2y, e1z * e2x - e1x * e2z, e1x * e2y - e1y * e2x};
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(v, 1f);
    }
}
