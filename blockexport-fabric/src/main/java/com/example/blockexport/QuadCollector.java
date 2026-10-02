package com.example.blockexport;

import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Um RenderContext "falso" que, em vez de desenhar, guarda os quads que o modelo emite.
 *
 * E o equivalente Fabric de pedir os quads ao jogo: qualquer modelo FRAPI (CopycatModel incluido) chama
 * context.getEmitter()/meshConsumer()/fallbackConsumer()/bakedModelConsumer() e nos entrega a geometria
 * ja com material, rotacao do blockstate e transformacoes aplicadas.
 *
 * O RenderContext e o QuadEmitter sao implementados com java.lang.reflect.Proxy de proposito: a interface
 * RenderContext mudou entre versoes da Fabric API (fallbackConsumer -> bakedModelConsumer, etc.) e assim
 * o codigo compila e roda nas duas variantes.
 */
final class QuadCollector {

    /**
     * Quad capturado (3 ou 4 vertices): posicao local ao bloco, UV (atlas ou textura), colorIndex, cullFace.
     *  - Quads de modelo (FRAPI): sprite != null, texture == null, color == -1 (tint vem do colorIndex).
     *  - Quads de BlockEntityRenderer: color = cor de vertice (RGB); sprite != null se usam o atlas de blocos,
     *    senao texture = textura inteira (entidade, bau, cama, placa...).
     */
    record Quad(float[] pos, float[] uv, int colorIndex, Direction cullFace,
                TextureAtlasSprite sprite, ResourceLocation texture, int color) {}

    /** Converte um BakedQuad vanilla direto em Quad (sem passar pela FRAPI). O sprite vem do proprio quad. */
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

    private static final Direction[] SIDES = Direction.values();

    private final SpriteFinder finder;
    private final MeshBuilder meshBuilder;
    private final QuadEmitter real;
    private final RenderMaterial defaultMaterial;
    private final List<RenderContext.QuadTransform> transforms = new ArrayList<>();
    private final List<Quad> out = new ArrayList<>();

    private final QuadEmitter emitterProxy;
    private final RenderContext contextProxy;

    private BlockState state;
    private long seed;
    private int emitted;

    QuadCollector(SpriteFinder finder) {
        this.finder = finder;
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        this.meshBuilder = renderer.meshBuilder();
        this.real = meshBuilder.getEmitter();
        this.defaultMaterial = renderer.materialFinder().find();

        ClassLoader cl = QuadCollector.class.getClassLoader();

        this.emitterProxy = (QuadEmitter) Proxy.newProxyInstance(cl, new Class<?>[]{QuadEmitter.class}, (proxy, m, a) -> {
            if (m.getName().equals("emit") && m.getParameterCount() == 0) {
                emit();
                return proxy;
            }
            Object r = call(m, real, a);
            return r == real ? proxy : r;
        });

        this.contextProxy = (RenderContext) Proxy.newProxyInstance(cl, new Class<?>[]{RenderContext.class}, (proxy, m, a) -> {
            switch (m.getName()) {
                case "getEmitter":
                    return emitterProxy;
                case "pushTransform":
                    transforms.add((RenderContext.QuadTransform) a[0]);
                    return null;
                case "popTransform":
                    transforms.remove(transforms.size() - 1);
                    return null;
                case "meshConsumer":
                    return (Consumer<Mesh>) mesh -> mesh.forEach(q -> {
                        real.copyFrom(q);
                        emit();
                    });
                case "fallbackConsumer":
                    return (Consumer<BakedModel>) model -> vanilla(model, state);
                case "bakedModelConsumer":
                    return bakedModelConsumer(cl, m.getReturnType());
                default:
                    return fallback(proxy, m, a);
            }
        });
    }

    // ------------------------------------------------------------------ API

    void begin(BlockState state, long seed) {
        this.state = state;
        this.seed = seed;
        this.out.clear();
        this.transforms.clear();
    }

    RenderContext context() { return contextProxy; }

    Supplier<RandomSource> randomSupplier() {
        return () -> {
            RandomSource r = RandomSource.create();
            r.setSeed(seed);
            return r;
        };
    }

    List<Quad> quads() { return out; }

    // ------------------------------------------------------------------ interno

    /** Chamado quando o modelo faz emitter.emit(): aplica as transformacoes e captura o quad. */
    private void emit() {
        boolean keep = true;
        for (int i = transforms.size() - 1; i >= 0 && keep; i--) {
            keep = transforms.get(i).transform(real);   // a transformacao mais recente roda primeiro
        }
        if (keep) capture();
        real.emit();                                    // reseta o emitter
        if (++emitted % 4096 == 0) meshBuilder.build(); // descarta a malha acumulada
    }

    private void capture() {
        float[] p = new float[12];
        float[] t = new float[8];
        float su = 0, sv = 0;
        for (int i = 0; i < 4; i++) {
            p[i * 3] = real.x(i);
            p[i * 3 + 1] = real.y(i);
            p[i * 3 + 2] = real.z(i);
            t[i * 2] = real.u(i);
            t[i * 2 + 1] = real.v(i);
            su += t[i * 2];
            sv += t[i * 2 + 1];
        }
        // O quad FRAPI nao guarda o sprite; descobrimos pelo centro da UV no atlas.
        TextureAtlasSprite sprite = finder.find(su / 4f, sv / 4f);
        out.add(new Quad(p, t, real.colorIndex(), real.cullFace(), sprite, null, -1));
    }

    /** Equivalente ao "fallback" do Indigo: modelos vanilla sao lidos via getQuads e re-emitidos. */
    private void vanilla(BakedModel model, BlockState s) {
        for (int i = -1; i < SIDES.length; i++) {
            Direction d = i < 0 ? null : SIDES[i];
            RandomSource r = RandomSource.create();
            r.setSeed(seed);
            for (BakedQuad q : model.getQuads(s, d, r)) {
                real.fromVanilla(q, defaultMaterial, d);
                emit();
            }
        }
    }

    private Object bakedModelConsumer(ClassLoader cl, Class<?> type) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{type}, (proxy, m, a) -> {
            if (m.getName().equals("accept") && a != null && a.length > 0 && a[0] instanceof BakedModel model) {
                BlockState s = a.length > 1 && a[1] instanceof BlockState bs ? bs : state;
                vanilla(model, s);
                return null;
            }
            return fallback(proxy, m, a);
        });
    }

    private static Object call(Method m, Object target, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Metodos que nao conhecemos: usa o default da interface, ou um valor neutro. */
    private static Object fallback(Object proxy, Method m, Object[] a) throws Throwable {
        if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, a);
        Class<?> t = m.getReturnType();
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == float.class) return 0f;
        if (t == long.class) return 0L;
        return null;
    }
}
