package com.example.blockexport;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MultiBufferSource "falso" para capturar o que um BlockEntityRenderer desenharia.
 *
 * O BER chama getBuffer(renderType) e escreve vertices (vertex/color/uv/normal/endVertex, direto ou via
 * putBulkData/ModelPart.render/SuperByteBuffer). Em vez de ir pra GPU, cada vertice cai aqui e, a cada 4
 * (QUADS) ou 3 (TRIANGLES) vertices, vira um QuadCollector.Quad.
 *
 * A textura vem do RenderType: o toString() dele lista o TextureStateShard com o caminho do .png. Usar o
 * texto (e nao reflexao/access widener) funciona igual em dev e em producao, em qualquer loader.
 *
 * Nao depende de Fabric: o localizador de sprite e injetado.
 */
final class BerCapture implements MultiBufferSource {

    private static final Pattern TEX = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9/_.\\-]+\\.png");

    private final BiFunction<Float, Float, TextureAtlasSprite> spriteFinder;
    private final Map<RenderType, Sink> sinks = new HashMap<>();
    private final List<QuadCollector.Quad> out = new ArrayList<>();
    private final Set<String> unresolved = new TreeSet<>();

    BerCapture(BiFunction<Float, Float, TextureAtlasSprite> spriteFinder) {
        this.spriteFinder = spriteFinder;
    }

    void begin() {
        sinks.clear();
        out.clear();
    }

    List<QuadCollector.Quad> quads() { return out; }

    /** RenderTypes cuja textura nao deu pra descobrir (ex.: fontes, texturas dinamicas sem .png). */
    Set<String> unresolved() { return unresolved; }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        return sinks.computeIfAbsent(renderType, Sink::new);
    }

    // ------------------------------------------------------------------

    private final class Sink implements VertexConsumer {
        private final int n;                    // vertices por primitiva (4 = quad, 3 = triangulo, 0 = ignorar)
        private final boolean blocksAtlas;
        private final ResourceLocation tex;

        private final float[] px = new float[12];
        private final float[] pu = new float[8];
        private int count;
        private int rgb = 0xFFFFFF;
        private boolean n0Set;
        private float n0x, n0y, n0z;

        // estado do vertice em construcao
        private double x, y, z;
        private float u, v;
        private int r = 255, g = 255, b = 255, a = 255;
        private boolean hasDefault;
        private boolean hasNormal;
        private float nx, ny, nz;

        Sink(RenderType rt) {
            VertexFormat.Mode mode = rt.mode();
            int vertices = mode == VertexFormat.Mode.QUADS ? 4 : mode == VertexFormat.Mode.TRIANGLES ? 3 : 0;

            Matcher m = TEX.matcher(rt.toString());
            ResourceLocation loc = m.find() ? ResourceLocation.tryParse(m.group()) : null;
            this.blocksAtlas = TextureAtlas.LOCATION_BLOCKS.equals(loc);
            this.tex = blocksAtlas ? null : loc;

            if (vertices > 0 && loc == null) {
                String s = rt.toString();
                unresolved.add(s.length() > 90 ? s.substring(0, 90) : s);
                vertices = 0;
            }
            this.n = vertices;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            if (!hasDefault) { this.r = r; this.g = g; this.b = b; this.a = a; }
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u; this.v = v;
            return this;
        }

        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.nx = x; this.ny = y; this.nz = z;
            this.hasNormal = true;
            return this;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            this.hasDefault = true;
            this.r = r; this.g = g; this.b = b; this.a = a;
        }

        @Override
        public void unsetDefaultColor() {
            this.hasDefault = false;
        }

        @Override
        public void endVertex() {
            if (n > 0) {
                int i = count;
                px[i * 3] = (float) x;
                px[i * 3 + 1] = (float) y;
                px[i * 3 + 2] = (float) z;
                pu[i * 2] = u;
                pu[i * 2 + 1] = v;
                if (i == 0) {
                    rgb = ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
                    n0Set = hasNormal;
                    n0x = nx; n0y = ny; n0z = nz;
                }
                if (++count == n) {
                    flush();
                    count = 0;
                }
            }
            hasNormal = false;
            if (!hasDefault) { r = 255; g = 255; b = 255; a = 255; }
        }

        private void flush() {
            float[] pos = Arrays.copyOf(px, n * 3);
            float[] uv = Arrays.copyOf(pu, n * 2);

            // Entidades costumam renderizar sem culling, entao o winding pode vir invertido.
            // Se o BER informou normal, usamos pra corrigir a ordem dos vertices.
            if (n0Set) {
                float[] gn = QuadCollector.faceNormal(pos);
                if (gn[0] * n0x + gn[1] * n0y + gn[2] * n0z < 0) {
                    for (int i = 0; i < n / 2; i++) {
                        int j = n - 1 - i;
                        for (int k = 0; k < 3; k++) {
                            float t = pos[i * 3 + k]; pos[i * 3 + k] = pos[j * 3 + k]; pos[j * 3 + k] = t;
                        }
                        for (int k = 0; k < 2; k++) {
                            float t = uv[i * 2 + k]; uv[i * 2 + k] = uv[j * 2 + k]; uv[j * 2 + k] = t;
                        }
                    }
                }
            }

            TextureAtlasSprite sprite = null;
            if (blocksAtlas) {
                float su = 0, sv = 0;
                for (int i = 0; i < n; i++) { su += uv[i * 2]; sv += uv[i * 2 + 1]; }
                sprite = spriteFinder.apply(su / n, sv / n);
                if (sprite == null) return;
            }
            out.add(new QuadCollector.Quad(pos, uv, -1, null, sprite, tex, rgb & 0xFFFFFF));
        }
    }
}
