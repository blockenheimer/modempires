package com.example.blockexport;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Tipos e utilitarios de geometria compartilhados pelo exportador.
 *
 * (Antes esta classe fabricava um RenderContext falso da FRAPI. Isso quebrava com o Indium, que exige o
 * contexto dele proprio. Agora quem monta o contexto e o renderizador do jogo; veja ModelExporter.rendererQuads.)
 */
final class QuadCollector {

    /**
     * Quad capturado (3 ou 4 vertices): posicao local ao bloco, UV (atlas ou textura), colorIndex, cullFace.
     *  - Quads de getQuads (vanilla): sprite != null, color == -1 (o tint vem do colorIndex via BlockColors).
     *  - Quads do renderizador / BlockEntityRenderer: color = cor de vertice (RGB); sprite != null se usam o
     *    atlas de blocos, senao texture = textura inteira (entidade, bau, cama, placa...).
     */
    record Quad(float[] pos, float[] uv, int colorIndex, Direction cullFace,
                TextureAtlasSprite sprite, ResourceLocation texture, int color) {}

    /** Converte um BakedQuad vanilla direto em Quad. O sprite vem do proprio quad. */
    static Quad fromBaked(BakedQuad q, Direction cullFace) {
        int[] d = q.getVertices();              // BLOCK format: 8 ints por vertice
        float[] p = new float[12];
        float[] t = new float[8];
        for (int i = 0; i < 4; i++) {
            p[i * 3] = Float.intBitsToFloat(d[i * 8]);
            p[i * 3 + 1] = Float.intBitsToFloat(d[i * 8 + 1]);
            p[i * 3 + 2] = Float.intBitsToFloat(d[i * 8 + 2]);
            t[i * 2] = Float.intBitsToFloat(d[i * 8 + 4]);
            t[i * 2 + 1] = Float.intBitsToFloat(d[i * 8 + 5]);
        }
        return new Quad(p, t, q.getTintIndex(), cullFace, q.getSprite(), null, -1);
    }

    /** Normal da face (quad: pelas diagonais; triangulo: pelas arestas). */
    static float[] faceNormal(float[] p) {
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
        if (len < 1e-8f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    private QuadCollector() {}
}
