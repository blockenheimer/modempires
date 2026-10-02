package com.example.blockexport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Espessura minima para impressao 3D, calculada a partir da geometria do proprio bloco.
 *
 * Trabalha so com arrays de floats (sem classes do Minecraft), pra poder ser testada fora do jogo.
 * As posicoes sao locais ao bloco (0..1). Dois problemas sao tratados, nesta ordem:
 *
 *  1) Parede fina (tapete, placa de 1 px, disco de engrenagem, tubo de parede fina, camada de neve...): duas
 *     faces paralelas, de lados opostos, mais proximas que o minimo e ligadas por faces laterais. A face que
 *     pode andar sem sair do bloco e afastada ate o minimo; os vertices dela sao movidos junto com as laterais.
 *     Nao depende de saber o que o bloco e: vale pra qualquer modelo de qualquer mod, e tambem pra BlockEntityRenderers.
 *
 *  2) Folhas sem espessura (plantas em X, trilhos, vinhas, bandeiras, cercas de vidro de 1 face...): um quad
 *     que nao tem nenhuma face "de costas" no mesmo bloco (um raio saindo dele pra dentro nao acerta nada)
 *     e uma folha. Ela vira uma placa: copia de tras + 4 laterais. Duas folhas coincidentes de lados opostos
 *     (o caso de plantas em X, que sao duas faces no mesmo plano) viram UMA placa, centrada no plano.
 *
 * A deteccao de "face de costas" olha nos dois sentidos da normal, entao quads com a ordem dos vertices
 * invertida (comum em BlockEntityRenderers) nao sao confundidos com folhas.
 *
 * As laterais novas usam o UV da borda da face (listra fina da textura, como o vidro do jogo faz).
 */
final class Solidify {

    /** Quad de entrada: posicoes (x,y,z por vertice) e UV (u,v por vertice), 3 ou 4 vertices. */
    record Src(float[] pos, float[] uv) {}

    /**
     * Quad de saida. src = indice do quad de entrada de onde herdar sprite/textura/cor.
     * original = true se e o proprio quad (movido/esticado); false se foi gerado (verso, laterais).
     */
    record Out(float[] pos, float[] uv, int src, boolean original) {}

    /** sheets = folhas viradas placa; walls = paredes finas engrossadas;
     *  rayLimited = bloco com quads demais, deteccao de folhas/paredes pulada. */
    record Result(List<Out> quads, int sheets, int walls, boolean rayLimited) {}

    private static final float EPS_HIT = 1e-3f;
    private static final int MAX_QUADS_FOR_RAYS = 4000;

    private Solidify() {}

    /**
     * @param minT  espessura minima em unidades de bloco (1 bloco = 1.0). Valores <= 0 desligam tudo.
     */
    static Result run(List<Src> in, float minT) {
        int n = in.size();
        List<Out> out = new ArrayList<>(n);
        if (n == 0 || minT <= 0f) {
            for (int i = 0; i < n; i++) out.add(new Out(in.get(i).pos(), in.get(i).uv(), i, true));
            return new Result(out, 0, 0, false);
        }
        minT = Math.min(minT, 0.5f);

        // copias de trabalho (nunca altera os arrays de entrada)
        float[][] pos = new float[n][];
        for (int i = 0; i < n; i++) pos[i] = in.get(i).pos().clone();

        boolean rayLimited = n > MAX_QUADS_FOR_RAYS;
        int walls = rayLimited ? 0 : fixThinWalls(pos, minT);

        // normais, validade e caixas (depois de mexer nas posicoes)
        float[][] nrm = new float[n][];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            nrm[i] = normal(pos[i]);
            valid[i] = nrm[i] != null;
        }

        // folha = quad valido sem nenhuma face "de costas" no bloco
        boolean[] sheet = new boolean[n];
        if (!rayLimited) {
            float[][][] box = boxes(pos);
            for (int i = 0; i < n; i++) if (valid[i]) sheet[i] = !hasBacking(i, pos, nrm, valid, box, minT);
        }

        // pares de folhas coincidentes com normais opostas
        int[] partner = new int[n];
        Arrays.fill(partner, -1);
        Map<String, List<Integer>> byKey = new HashMap<>();
        for (int i = 0; i < n; i++) if (sheet[i]) byKey.computeIfAbsent(key(pos[i]), k -> new ArrayList<>(2)).add(i);
        for (List<Integer> g : byKey.values()) {
            for (int a = 0; a < g.size(); a++) {
                int i = g.get(a);
                if (partner[i] != -1) continue;
                for (int b = a + 1; b < g.size(); b++) {
                    int j = g.get(b);
                    if (partner[j] != -1) continue;
                    if (dot(nrm[i], nrm[j]) < -0.99f) { partner[i] = j; partner[j] = i; break; }
                }
            }
        }

        int sheets = 0;
        boolean[] done = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (done[i]) continue;
            Src s = in.get(i);
            if (!sheet[i]) {
                out.add(new Out(pos[i], s.uv(), i, true));
                done[i] = true;
                continue;
            }
            sheets++;
            int j = partner[i];
            float[] nn = nrm[i];
            float[] shift = new float[3];
            if (j >= 0) {
                // placa centrada no plano: frente = i (+n*h), verso = j (+n_j*h = -n*h)
                float h = minT / 2f;
                fitShift(pos[i], nn, h, -h, shift);
                float[] front = move(pos[i], nn, h, shift);
                float[] back = move(pos[j], nrm[j], h, shift);
                out.add(new Out(front, s.uv(), i, true));
                out.add(new Out(back, in.get(j).uv(), j, true));
                float[] ringA = front;
                float[] ringB = move(pos[i], nn, -h, shift);
                addSides(out, ringA, ringB, s.uv(), i);
                done[i] = true;
                done[j] = true;
            } else {
                // placa pra tras: frente = quad original, verso = copia deslocada -n*T com winding invertido
                fitShift(pos[i], nn, 0f, -minT, shift);
                float[] front = move(pos[i], nn, 0f, shift);
                float[] ringB = move(pos[i], nn, -minT, shift);
                int vc = front.length / 3;
                float[] backPos = new float[vc * 3];
                float[] backUv = new float[vc * 2];
                for (int k = 0; k < vc; k++) {
                    int r = vc - 1 - k;
                    System.arraycopy(ringB, r * 3, backPos, k * 3, 3);
                    System.arraycopy(s.uv(), r * 2, backUv, k * 2, 2);
                }
                out.add(new Out(front, s.uv(), i, true));
                out.add(new Out(backPos, backUv, i, false));
                addSides(out, front, ringB, s.uv(), i);
                done[i] = true;
            }
        }
        return new Result(out, sheets, walls, rayLimited);
    }

    // ------------------------------------------------------------------ folhas

    /**
     * Existe algum quad do bloco, com normal oposta, a pelo menos minT deste (raio do centro, nos dois sentidos
     * da normal)? Faces coladas (distancia ~0) e faces mais proximas que minT que nao sao uma parede ligada
     * nao contam como "costas".
     */
    private static boolean hasBacking(int i, float[][] pos, float[][] nrm, boolean[] valid, float[][][] box, float minT) {
        float[] o = centroid(pos[i]);
        for (int dir = -1; dir <= 1; dir += 2) {
            float[] d = {dir * nrm[i][0], dir * nrm[i][1], dir * nrm[i][2]};
            for (int j = 0; j < pos.length; j++) {
                if (j == i || !valid[j] || dot(nrm[j], nrm[i]) > -0.1f) continue;
                if (!rayHitsBox(o, d, box[0][j], box[1][j])) continue;
                float h = rayQuad(o, d, pos[j]);
                if (h >= minT - 1e-4f) return true;
            }
        }
        return false;
    }

    /** Menor distancia positiva (> EPS_HIT) do raio ate o quad, ou -1. */
    private static float rayQuad(float[] o, float[] d, float[] q) {
        int qc = q.length / 3;
        float best = -1f;
        for (int t = 1; t + 1 < qc; t++) {
            float h = rayTriangle(o, d, q, 0, t, t + 1);
            if (h > EPS_HIT && (best < 0 || h < best)) best = h;
        }
        return best;
    }

    private static float[][][] boxes(float[][] pos) {
        float[][] mn = new float[pos.length][3], mx = new float[pos.length][3];
        for (int i = 0; i < pos.length; i++) {
            Arrays.fill(mn[i], Float.MAX_VALUE);
            Arrays.fill(mx[i], -Float.MAX_VALUE);
            for (int k = 0; k < pos[i].length; k += 3)
                for (int a = 0; a < 3; a++) { mn[i][a] = Math.min(mn[i][a], pos[i][k + a]); mx[i][a] = Math.max(mx[i][a], pos[i][k + a]); }
        }
        return new float[][][]{mn, mx};
    }

    /** Filtro barato: o raio (t >= 0) passa pela caixa do quad? */
    private static boolean rayHitsBox(float[] o, float[] d, float[] mn, float[] mx) {
        float t0 = 0f, t1 = Float.MAX_VALUE;
        for (int a = 0; a < 3; a++) {
            float lo = mn[a] - 1e-3f, hi = mx[a] + 1e-3f;
            if (Math.abs(d[a]) < 1e-9f) {
                if (o[a] < lo || o[a] > hi) return false;
            } else {
                float inv = 1f / d[a], ta = (lo - o[a]) * inv, tb = (hi - o[a]) * inv;
                if (ta > tb) { float x = ta; ta = tb; tb = x; }
                t0 = Math.max(t0, ta);
                t1 = Math.min(t1, tb);
                if (t0 > t1) return false;
            }
        }
        return true;
    }

    private static float[] centroid(float[] p) {
        int vc = p.length / 3;
        float cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < vc; k++) { cx += p[k * 3]; cy += p[k * 3 + 1]; cz += p[k * 3 + 2]; }
        return new float[]{cx / vc, cy / vc, cz / vc};
    }

    // ------------------------------------------------------------------ paredes finas

    /** Engrossa pares de faces paralelas opostas, ligadas por laterais, mais proximas que minT. Retorna quantas. */
    private static int fixThinWalls(float[][] pos, float minT) {
        int n = pos.length;
        float[][] nrm = new float[n][];
        float[][] cen = new float[n][];
        for (int i = 0; i < n; i++) { nrm[i] = normal(pos[i]); cen[i] = centroid(pos[i]); }

        List<int[]> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (nrm[i] == null) continue;
            for (int j = i + 1; j < n; j++) {
                if (nrm[j] == null || dot(nrm[i], nrm[j]) >= -0.99f) continue;
                float dd = dot(sub(cen[j], cen[i]), nrm[i]);
                float d = Math.abs(dd);
                if (d <= EPS_HIT || d >= minT - 1e-4f) continue;
                float sg = Math.signum(dd);
                float[] toJ = {sg * nrm[i][0], sg * nrm[i][1], sg * nrm[i][2]};
                float[] toI = {-toJ[0], -toJ[1], -toJ[2]};
                // as faces precisam se sobrepor (o centro de uma cai sobre a outra)
                if (rayQuad(cen[i], toJ, pos[j]) < 0 && rayQuad(cen[j], toI, pos[i]) < 0) continue;
                if (bridged(i, j, pos)) cand.add(new int[]{i, j});
            }
        }

        int fixed = 0;
        for (int[] c : cand) {
            int i = c[0], j = c[1];
            float[] ni = normal(pos[i]), nj = normal(pos[j]);
            if (ni == null || nj == null) continue;
            float dd = dot(sub(centroid(pos[j]), centroid(pos[i])), ni);
            float d = Math.abs(dd);
            if (d >= minT - 1e-4f) continue;                       // outra correcao ja engrossou
            float delta = minT - d, sg = Math.signum(dd);
            float[] awayI = {-sg * ni[0], -sg * ni[1], -sg * ni[2]};
            float[] awayJ = {sg * ni[0], sg * ni[1], sg * ni[2]};

            // prefere mover a face que anda no sentido positivo do eixo, se continuar dentro do bloco
            boolean iPos = axisSign(awayI) >= 0;
            int first = iPos ? i : j, second = iPos ? j : i;
            float[] dirFirst = iPos ? awayI : awayJ, dirSecond = iPos ? awayJ : awayI;
            int mover = first;
            float[] dir = dirFirst;
            if (!inBounds(pos[first], dirFirst, delta) && inBounds(pos[second], dirSecond, delta)) { mover = second; dir = dirSecond; }
            moveWithSharedVertices(pos, mover, dir, delta);
            fixed++;
        }
        return fixed;
    }

    /** Existe uma face lateral que toca (>= 2 vertices) tanto a face i quanto a face j? */
    private static boolean bridged(int i, int j, float[][] pos) {
        java.util.Set<Long> ki = keys(pos[i]), kj = keys(pos[j]);
        for (int k = 0; k < pos.length; k++) {
            if (k == i || k == j) continue;
            int hi = 0, hj = 0;
            for (int v = 0; v < pos[k].length / 3; v++) {
                long key = key(pos[k], v);
                if (ki.contains(key)) hi++;
                if (kj.contains(key)) hj++;
            }
            if (hi >= 2 && hj >= 2) return true;
        }
        return false;
    }

    private static void moveWithSharedVertices(float[][] pos, int mover, float[] dir, float delta) {
        java.util.Set<Long> ks = keys(pos[mover]);
        // chaves calculadas antes de mexer em qualquer posicao
        List<int[]> hits = new ArrayList<>();
        for (int q = 0; q < pos.length; q++)
            for (int v = 0; v < pos[q].length / 3; v++)
                if (ks.contains(key(pos[q], v))) hits.add(new int[]{q, v});
        for (int[] h : hits) {
            pos[h[0]][h[1] * 3] += dir[0] * delta;
            pos[h[0]][h[1] * 3 + 1] += dir[1] * delta;
            pos[h[0]][h[1] * 3 + 2] += dir[2] * delta;
        }
    }

    private static boolean inBounds(float[] pos, float[] dir, float delta) {
        int a = Math.abs(dir[0]) > 0.99f ? 0 : Math.abs(dir[1]) > 0.99f ? 1 : Math.abs(dir[2]) > 0.99f ? 2 : -1;
        if (a < 0) return true;
        float c = pos[a];
        if (c < -1e-3f || c > 1f + 1e-3f) return true;           // geometria fora do bloco (BER): sem regra de limite
        float after = c + dir[a] * delta;
        return after >= -1e-3f && after <= 1f + 1e-3f;
    }

    private static float axisSign(float[] d) {
        int a = Math.abs(d[0]) >= Math.abs(d[1]) && Math.abs(d[0]) >= Math.abs(d[2]) ? 0 : Math.abs(d[1]) >= Math.abs(d[2]) ? 1 : 2;
        return d[a];
    }

    private static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    private static java.util.Set<Long> keys(float[] p) {
        java.util.Set<Long> s = new java.util.HashSet<>();
        for (int v = 0; v < p.length / 3; v++) s.add(key(p, v));
        return s;
    }

    private static long key(float[] p, int v) {
        long x = Math.round(p[v * 3] * 1000) + 0x10000L, y = Math.round(p[v * 3 + 1] * 1000) + 0x10000L, z = Math.round(p[v * 3 + 2] * 1000) + 0x10000L;
        return (x << 42) | (y << 21) | z;
    }

    /** Moller-Trumbore: distancia t ao longo de d, ou -1. */
    private static float rayTriangle(float[] o, float[] d, float[] q, int ia, int ib, int ic) {
        float ax = q[ia * 3], ay = q[ia * 3 + 1], az = q[ia * 3 + 2];
        float e1x = q[ib * 3] - ax, e1y = q[ib * 3 + 1] - ay, e1z = q[ib * 3 + 2] - az;
        float e2x = q[ic * 3] - ax, e2y = q[ic * 3 + 1] - ay, e2z = q[ic * 3 + 2] - az;
        float px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-9f) return -1f;
        float inv = 1f / det;
        float tx = o[0] - ax, ty = o[1] - ay, tz = o[2] - az;
        float u = (tx * px + ty * py + tz * pz) * inv;
        if (u < -1e-4f || u > 1f + 1e-4f) return -1f;
        float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
        float v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
        if (v < -1e-4f || u + v > 1f + 1e-4f) return -1f;
        return (e2x * qx + e2y * qy + e2z * qz) * inv;
    }

    /**
     * Se a normal e alinhada a um eixo e a placa (de frontOff a backOff ao longo da normal) sairia de 0..1,
     * calcula um deslocamento ao longo do eixo que a traz de volta pra dentro do bloco.
     */
    private static void fitShift(float[] pos, float[] n, float frontOff, float backOff, float[] shiftOut) {
        int a = Math.abs(n[0]) > 0.99f ? 0 : Math.abs(n[1]) > 0.99f ? 1 : Math.abs(n[2]) > 0.99f ? 2 : -1;
        if (a < 0) return;
        float c0 = pos[a];
        if (c0 < -1e-3f || c0 > 1f + 1e-3f) return;
        float sg = Math.signum(n[a]);
        float cf = c0 + sg * frontOff, cb = c0 + sg * backOff;
        float lo = Math.min(cf, cb), hi = Math.max(cf, cb);
        if (lo < 0f) shiftOut[a] = -lo;
        else if (hi > 1f) shiftOut[a] = 1f - hi;
    }

    private static float[] move(float[] p, float[] n, float dist, float[] shift) {
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i += 3) {
            r[i] = p[i] + n[0] * dist + shift[0];
            r[i + 1] = p[i + 1] + n[1] * dist + shift[1];
            r[i + 2] = p[i + 2] + n[2] * dist + shift[2];
        }
        return r;
    }

    /** 4 laterais ligando o anel A (frente) ao anel B (verso), viradas pra fora. UV = so a borda. */
    private static void addSides(List<Out> out, float[] a, float[] b, float[] uv, int src) {
        int vc = a.length / 3;
        for (int k = 0; k < vc; k++) {
            int j = (k + 1) % vc;
            float[] p = new float[12];
            float[] t = new float[8];
            copy3(a, j, p, 0); copy3(a, k, p, 1); copy3(b, k, p, 2); copy3(b, j, p, 3);
            copy2(uv, j, t, 0); copy2(uv, k, t, 1); copy2(uv, k, t, 2); copy2(uv, j, t, 3);
            out.add(new Out(p, t, src, false));
        }
    }

    private static void copy3(float[] s, int si, float[] d, int di) { System.arraycopy(s, si * 3, d, di * 3, 3); }
    private static void copy2(float[] s, int si, float[] d, int di) { System.arraycopy(s, si * 2, d, di * 2, 2); }

    // ------------------------------------------------------------------ geometria

    /** Normal unitaria (quad: diagonais; triangulo: arestas) ou null se degenerado. Mesma regra do QuadCollector. */
    static float[] normal(float[] p) {
        int n = p.length / 3;
        float e1x, e1y, e1z, e2x, e2y, e2z;
        if (n >= 4) {
            e1x = p[6] - p[0]; e1y = p[7] - p[1]; e1z = p[8] - p[2];
            e2x = p[9] - p[3]; e2y = p[10] - p[4]; e2z = p[11] - p[5];
        } else {
            e1x = p[3] - p[0]; e1y = p[4] - p[1]; e1z = p[5] - p[2];
            e2x = p[6] - p[0]; e2y = p[7] - p[1]; e2z = p[8] - p[2];
        }
        float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-7f) return null;
        return new float[]{nx / len, ny / len, nz / len};
    }

    private static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static String key(float[] p) {
        int n = p.length / 3;
        String[] v = new String[n];
        for (int i = 0; i < n; i++)
            v[i] = Math.round(p[i * 3] * 1000) + "," + Math.round(p[i * 3 + 1] * 1000) + "," + Math.round(p[i * 3 + 2] * 1000);
        Arrays.sort(v);
        return String.join("|", v);
    }
}
