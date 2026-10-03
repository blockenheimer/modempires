package com.example.blockexport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Exporta blocos pelos mesmos caminhos de render do jogo:
 *
 *  1) Modelo do bloco:  BakedModel.emitBlockQuads(view, state, pos, random, context)   (FRAPI, copycats incluidos)
 *  2) BlockEntityRenderer: renderer.render(be, ..., MultiBufferSource, ...)               (eixos, engrenagens, baus, camas...)
 *
 * Nos dois casos o "context"/"buffer" e nosso e so guarda a geometria em vez de desenhar.
 *
 * Saida: model.obj, model.mtl, textures/*.png (um PNG por sprite/tint ou textura inteira), blocks.json.
 */
public final class ModelExporter {

    public record Result(int blocks, int quads, int materials, Map<String, Integer> skipped) {}

    private record TexImg(NativeImage img, boolean alpha) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path dir;
    private final boolean cull;
    private final boolean ber;
    private final boolean print;
    private final float printScale;        // mm por bloco (o OBJ de impressao sai em milimetros)
    private final float printMinBlocks;    // espessura minima, em blocos (0 = desligado)
    private final float solidBlocks;       // "solidify": espessura (em blocos) dos planos soltos, mantendo a silhueta dos pixels (0 = desligado)
    private final boolean pixels;          // cada texel vira uma face com pixel proprio no atlas (pra pintar fora do jogo)
    private int pixelFaceCount = 0;
    private int printBlockCounter = 0;
    private final Minecraft mc = Minecraft.getInstance();

    private NativeImage atlasImg;
    private final Map<ResourceLocation, NativeImage> spriteCache = new HashMap<>();
    private final Map<ResourceLocation, Boolean> spriteAlpha = new HashMap<>();
    private final Map<ResourceLocation, TexImg> texImages = new HashMap<>();
    private final Set<String> texDone = new HashSet<>();
    private final Map<String, String> mtl = new LinkedHashMap<>();
    private final Map<String, Integer> skipped = new TreeMap<>();
    private final JsonArray errorsJson = new JsonArray();
    private final Set<String> errorKeys = new HashSet<>();

    // modo impressao 3D: faces ficam em memoria ate o fim, pra remover faces internas e montar o atlas
    private record PrintFace(float[] p, float[] t, String tex, int block, boolean culled) {}
    private record K3(int a, int b, int c) {}
    private record K2(int a, int b) {}
    private static final int ATLAS_PAD = 2, ATLAS_MAX = 8192;
    private final List<PrintFace> printFaces = new ArrayList<>();
    private final Map<String, NativeImage> printImages = new LinkedHashMap<>();
    private int printRemoved = 0;

    private BufferedWriter wv, wvt, wvn, wf;
    private int vi = 1, ni = 1, quadCount = 0;
    private String curMat;

    public ModelExporter(Path dir, boolean cull, boolean ber, boolean print, float mmPerBlock, float minThicknessMm) {
        this(dir, cull, ber, print, mmPerBlock, minThicknessMm, 0f, false);
    }

    /**
     * @param solidPx espessura do "solidify" em pixels de textura (1 px = 1/16 de bloco); 0 = desligado
     * @param pixels  true = cada texel vira uma face, com um pixel proprio no atlas
     * solidify e pixels usam a malha unica (model_print.obj), entao ligam o modo impressao sozinhos.
     */
    public ModelExporter(Path dir, boolean cull, boolean ber, boolean print, float mmPerBlock, float minThicknessMm,
                         float solidPx, boolean pixels) {
        this.dir = dir;
        this.print = print || solidPx > 0 || pixels;
        this.cull = cull || this.print;      // impressao sempre remove faces encobertas
        this.ber = ber;
        this.printScale = mmPerBlock > 0 ? mmPerBlock : 10f;
        this.printMinBlocks = minThicknessMm > 0 ? minThicknessMm / this.printScale : 0f;
        this.solidBlocks = solidPx > 0 ? solidPx / 16f : 0f;
        this.pixels = pixels;
    }

    public Result run(ClientLevel level, BlockPos p1, BlockPos p2) throws IOException {
        Files.createDirectories(dir.resolve("textures"));
        captureAtlas();

        TextureAtlas atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        SpriteFinder finder = SpriteFinder.get(atlas);
        BerCapture blockCapture = new BerCapture((u, v) -> finder.find(u, v), true);   // blocos (cinza -> branco)
        BerCapture berCapture = new BerCapture((u, v) -> finder.find(u, v), false);    // BlockEntityRenderers
        ExportView view = new ExportView(level);

        BlockPos min = new BlockPos(Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ()));

        Path[] tmp = {dir.resolve("_v.tmp"), dir.resolve("_vt.tmp"), dir.resolve("_vn.tmp"), dir.resolve("_f.tmp")};
        wv = Files.newBufferedWriter(tmp[0]);
        wvt = Files.newBufferedWriter(tmp[1]);
        wvn = Files.newBufferedWriter(tmp[2]);
        wf = Files.newBufferedWriter(tmp[3]);

        JsonArray blocksJson = new JsonArray();
        int blocks = 0;

        // Ambient occlusion desligado durante a exportacao inteira (cor de vertice sem AO embutido).
        // IMPORTANTE: mudar essa opcao dispara levelRenderer.allChanged() (recarrega todos os chunks),
        // entao so pode ser feito UMA vez aqui, nunca por bloco.
        boolean aoWas = mc.options.ambientOcclusion().get();
        if (aoWas) mc.options.ambientOcclusion().set(false);

        try {
            for (BlockPos mutable : BlockPos.betweenClosed(min, max)) {
                BlockPos pos = mutable.immutable();
                BlockState st = level.getBlockState(pos);
                if (st.isAir()) continue;
                String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(st.getBlock()));

                BlockEntity be = level.getBlockEntity(pos);
                boolean hasModel = st.getRenderShape() == RenderShape.MODEL;
                boolean hasBer = ber && be != null && mc.getBlockEntityRenderDispatcher().getRenderer(be) != null;

                if (!hasModel && !hasBer) {
                    skipped.merge(id + " [renderShape=" + st.getRenderShape() + ", sem BER]", 1, Integer::sum);
                    continue;
                }
                try {
                    JsonObject info = exportBlock(level, view, blockCapture, berCapture, pos, st, id, min, be, hasModel);
                    if (info != null) {
                        blocksJson.add(info);
                        blocks++;
                    }
                } catch (Throwable t) {
                    skipped.merge(id + " [erro: " + t.getClass().getSimpleName() + "]", 1, Integer::sum);
                    logError(id, "export", t);
                }
            }
        } finally {
            if (aoWas) mc.options.ambientOcclusion().set(true);
            wv.close(); wvt.close(); wvn.close(); wf.close();
            atlasImg.close();
            spriteCache.values().forEach(NativeImage::close);
            texImages.values().forEach(t -> t.img().close());
        }
        berCapture.unresolved().forEach(s -> skipped.merge("BER sem textura resolvida: " + s, 1, Integer::sum));

        if (print) {
            writePrint();
            for (Path t : tmp) Files.deleteIfExists(t);
        } else {
        // MTL
        StringBuilder mtlText = new StringBuilder("# Block Export\n");
        mtl.values().forEach(mtlText::append);
        Files.writeString(dir.resolve("model.mtl"), mtlText.toString());

        // OBJ = header + v + vt + vn + f
        try (OutputStream out = Files.newOutputStream(dir.resolve("model.obj"))) {
            out.write("# Block Export (1 bloco = 1 unidade, Y para cima)\nmtllib model.mtl\n".getBytes(StandardCharsets.UTF_8));
            for (Path t : tmp) Files.copy(t, out);
        }
        for (Path t : tmp) Files.deleteIfExists(t);

        }

        // JSON
        JsonObject root = new JsonObject();
        JsonArray origin = new JsonArray();
        origin.add(min.getX()); origin.add(min.getY()); origin.add(min.getZ());
        root.add("originWorldPos", origin);
        root.addProperty("cull", cull);
        root.addProperty("blockEntityRenderers", ber);
        root.addProperty("printMode", print);
        root.addProperty("printFacesRemoved", printRemoved);
        root.addProperty("printMmPerBlock", printScale);
        root.addProperty("printMinThicknessMm", printMinBlocks * printScale);
        root.addProperty("solidThicknessPx", solidBlocks * 16f);
        root.addProperty("pixelFaces", pixels);
        root.addProperty("pixelFaceCount", pixelFaceCount);
        JsonObject sk = new JsonObject();
        skipped.forEach(sk::addProperty);
        root.add("skipped", sk);
        root.add("errors", errorsJson);
        root.add("blocks", blocksJson);
        Files.writeString(dir.resolve("blocks.json"), GSON.toJson(root));

        return new Result(blocks, quadCount, print ? printImages.size() : mtl.size(), skipped);
    }

    // ------------------------------------------------------------------ bloco

    private JsonObject exportBlock(ClientLevel level, ExportView view, BerCapture blockCapture, BerCapture berCapture,
                                   BlockPos pos, BlockState st, String id, BlockPos min,
                                   BlockEntity be, boolean hasModel) throws IOException {
        List<QuadCollector.Quad> quads = new ArrayList<>();

        // 1) modelo do bloco
        //    - modelo vanilla simples: getQuads direto
        //    - modelo dinamico (copycat, textura conectada...): renderizador do proprio jogo
        //      (BlockRenderDispatcher.renderBatched), que monta o contexto certo (Indigo/Indium/Sodium) e
        //      resolve o material embrulhado via RenderAttachment do ExportView
        //    - se isso falhar ou vier vazio: reserva com getQuads (sem o material embrulhado)
        boolean fallback = false;
        if (hasModel) {
            BakedModel model = mc.getBlockRenderer().getBlockModel(st);
            if (model.isVanillaAdapter()) {
                quads.addAll(vanillaQuads(level, model, st, pos));
            } else {
                List<QuadCollector.Quad> viaRenderer = null;
                try {
                    viaRenderer = rendererQuads(view, blockCapture, st, pos);
                } catch (Throwable t) {
                    logError(id, "renderer", t);
                    skipped.merge(id + " [renderer falhou: " + t.getClass().getSimpleName() + ", usou reserva vanilla]", 1, Integer::sum);
                }
                if (viaRenderer == null || viaRenderer.isEmpty()) {
                    quads.addAll(vanillaQuads(level, model, st, pos));
                    fallback = true;
                } else {
                    quads.addAll(viaRenderer);
                }
            }
        }

        // 2) BlockEntityRenderer (eixos, engrenagens, baus, camas, ...)
        int berQuads = 0;
        if (ber && be != null) {
            berCapture.begin();
            try {
                if (renderBer(be, berCapture)) {
                    berQuads = berCapture.quads().size();
                    quads.addAll(berCapture.quads());
                }
            } catch (Throwable t) {
                skipped.merge(id + " [BER erro: " + t.getClass().getSimpleName() + "]", 1, Integer::sum);
                logError(id, "BER", t);
            }
        }

        // (o offset aleatorio de flores/bambu ja vem embutido nos quads: o renderizador aplica, e vanillaQuads soma)
        float ox = pos.getX() - min.getX();
        float oy = pos.getY() - min.getY();
        float oz = pos.getZ() - min.getZ();

        String objName = safe((pos.getX() - min.getX()) + "_" + (pos.getY() - min.getY()) + "_" + (pos.getZ() - min.getZ()) + "_" + id);
        BlockColors colors = mc.getBlockColors();
        boolean opened = false;
        int n = 0;
        Set<String> sprites = new LinkedHashSet<>();

        if (print) n = collectPrint(level, pos, st, quads, ox, oy, oz, sprites);
        else for (QuadCollector.Quad q : quads) {
            if (q.sprite() == null && q.texture() == null) continue;
            if (cull && q.cullFace() != null
                && !Block.shouldRenderFace(st, level, pos, q.cullFace(), pos.relative(q.cullFace()))) continue;

            if (!opened) {
                wf.write("o " + objName + "\n");
                opened = true;
                curMat = null;
            }
            int tint = q.color() != -1 ? q.color()
                : q.colorIndex() != -1 ? colors.getColor(st, level, pos, q.colorIndex()) : -1;
            writeQuad(q, tint & 0xFFFFFF, ox, oy, oz);
            sprites.add(q.sprite() != null ? q.sprite().contents().name().toString() : q.texture().toString());
            n++;
        }
        if (n == 0) return null;
        quadCount += n;

        JsonObject o = new JsonObject();
        o.addProperty("object", objName);
        JsonArray rel = new JsonArray();
        rel.add(pos.getX() - min.getX()); rel.add(pos.getY() - min.getY()); rel.add(pos.getZ() - min.getZ());
        o.add("relPos", rel);
        JsonArray abs = new JsonArray();
        abs.add(pos.getX()); abs.add(pos.getY()); abs.add(pos.getZ());
        o.add("worldPos", abs);
        o.addProperty("block", id);
        o.addProperty("state", st.toString());
        JsonObject props = new JsonObject();
        for (Property<?> p : st.getProperties()) props.addProperty(p.getName(), propValue(st, p));
        o.add("properties", props);

        if (be != null) {
            try {
                CompoundTag tag = be.saveWithoutMetadata();
                o.add("blockEntity", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag));
                if (tag.contains("Material", Tag.TAG_COMPOUND)) {
                    o.add("wrappedMaterial", NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, tag.get("Material")));
                }
            } catch (Throwable ignored) {}
        }
        JsonArray sp = new JsonArray();
        sprites.forEach(sp::add);
        o.add("sprites", sp);
        o.addProperty("quads", n);
        o.addProperty("berQuads", berQuads);
        o.addProperty("fallbackVanilla", fallback);
        return o;
    }

    /**
     * Roda o BlockEntityRenderer com PoseStack identidade (os vertices saem em coordenadas locais ao bloco)
     * e com o nosso MultiBufferSource. Retorna false se o bloco nao tem renderer.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean renderBer(BlockEntity be, BerCapture capture) {
        BlockEntityRenderer renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null) return false;
        renderer.render(be, 0f, new PoseStack(), capture, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        return true;
    }

    /** Quads de um modelo via BakedModel.getQuads (caminho vanilla), ja com o offset aleatorio do bloco. */
    private List<QuadCollector.Quad> vanillaQuads(ClientLevel level, BakedModel model, BlockState st, BlockPos pos) {
        List<QuadCollector.Quad> res = new ArrayList<>();
        Vec3 off = st.getOffset(level, pos);
        RandomSource rand = RandomSource.create();
        for (int i = -1; i < 6; i++) {
            Direction d = i < 0 ? null : Direction.values()[i];
            rand.setSeed(st.getSeed(pos));
            for (BakedQuad q : model.getQuads(st, d, rand)) {
                QuadCollector.Quad quad = QuadCollector.fromBaked(q, d);
                float[] p = quad.pos();
                for (int k = 0; k < 4; k++) {
                    p[k * 3] += (float) off.x;
                    p[k * 3 + 1] += (float) off.y;
                    p[k * 3 + 2] += (float) off.z;
                }
                res.add(quad);
            }
        }
        return res;
    }

    /**
     * Renderiza o bloco com o renderizador do jogo, mas escrevendo num VertexConsumer nosso.
     * E o mesmo caminho usado por pistoes e blocos caindo; o Fabric/Indium/Indigo desviam modelos FRAPI
     * (copycats, CT do Create...) pro contexto deles, e o que sai sao vertices comuns.
     * O ambient occlusion ja foi desligado em run() (uma vez so, por causa do allChanged()).
     */
    private List<QuadCollector.Quad> rendererQuads(ExportView view, BerCapture capture, BlockState st, BlockPos pos) {
        capture.begin();
        mc.getBlockRenderer().renderBatched(st, pos, view, new PoseStack(),
            capture.getBuffer(RenderType.solid()), cull && !print, RandomSource.create(st.getSeed(pos)));
        return new ArrayList<>(capture.quads());
    }

    /** Guarda o stack trace (1 por tipo de erro) em blocks.json > errors e imprime no log do jogo. */
    private void logError(String blockId, String stage, Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        String key = stage + "|" + t.getClass().getName() + "|" + (st.length > 0 ? st[0].toString() : "?");
        if (!errorKeys.add(key) || errorKeys.size() > 25) return;

        JsonObject e = new JsonObject();
        e.addProperty("block", blockId);
        e.addProperty("stage", stage);
        e.addProperty("exception", t.toString());
        JsonArray frames = new JsonArray();
        for (int i = 0; i < Math.min(st.length, 14); i++) frames.add(st[i].toString());
        e.add("stack", frames);
        errorsJson.add(e);
        t.printStackTrace();
    }

    private static <T extends Comparable<T>> String propValue(BlockState s, Property<T> p) {
        return p.getName(s.getValue(p));
    }

    // ------------------------------------------------------------------ quad

    private void writeQuad(QuadCollector.Quad q, int tint, float ox, float oy, float oz) throws IOException {
        TextureAtlasSprite sprite = q.sprite();
        float[] pp = q.pos();
        float[] uv = q.uv();
        int vc = pp.length / 3;

        float u0 = 0, du = 1, v0 = 0, dv = 1;
        if (sprite != null) {
            u0 = sprite.getU0(); du = sprite.getU1() - u0;
            v0 = sprite.getV0(); dv = sprite.getV1() - v0;
            if (du == 0) du = 1;
            if (dv == 0) dv = 1;
        }

        float[] p = new float[vc * 3];
        float[] t = new float[vc * 2];
        for (int i = 0; i < vc; i++) {
            p[i * 3] = pp[i * 3] + ox;
            p[i * 3 + 1] = pp[i * 3 + 1] + oy;
            p[i * 3 + 2] = pp[i * 3 + 2] + oz;
            // sprite: UV do atlas -> UV local (0..1). textura inteira: UV ja e 0..1. V invertido pro OBJ.
            t[i * 2] = (uv[i * 2] - u0) / du;
            t[i * 2 + 1] = 1f - (uv[i * 2 + 1] - v0) / dv;
        }

        float[] nrm = QuadCollector.faceNormal(p);

        String mat = sprite != null ? material(sprite, tint) : materialTex(q.texture(), tint);
        if (!mat.equals(curMat)) {
            wf.write("usemtl " + mat + "\n");
            curMat = mat;
        }

        for (int i = 0; i < vc; i++) {
            wv.write("v " + f(p[i * 3]) + " " + f(p[i * 3 + 1]) + " " + f(p[i * 3 + 2]) + "\n");
            wvt.write("vt " + f(t[i * 2]) + " " + f(t[i * 2 + 1]) + "\n");
        }
        wvn.write("vn " + f(nrm[0]) + " " + f(nrm[1]) + " " + f(nrm[2]) + "\n");
        StringBuilder face = new StringBuilder("f");
        for (int i = 0; i < vc; i++) face.append(' ').append(vi + i).append('/').append(vi + i).append('/').append(ni);
        wf.write(face.append('\n').toString());
        vi += vc;
        ni++;
    }

    // ------------------------------------------------------------------ modo impressao 3D

    /**
     * Modo impressao: converte os quads de UM bloco em faces, decide quais sao encobertas (pela geometria da borda do
     * bloco), engrossa o que for fino demais e so entao descarta as encobertas e move pra posicao do bloco.
     */
    private int collectPrint(ClientLevel level, BlockPos pos, BlockState st, List<QuadCollector.Quad> quads,
                             float ox, float oy, float oz, Set<String> sprites) throws IOException {
        BlockColors colors = mc.getBlockColors();
        int block = ++printBlockCounter;
        List<PrintFace> local = new ArrayList<>();

        for (QuadCollector.Quad q : quads) {
            if (q.sprite() == null && q.texture() == null) continue;
            TextureAtlasSprite sprite = q.sprite();
            int tint = (q.color() != -1 ? q.color()
                : q.colorIndex() != -1 ? colors.getColor(st, level, pos, q.colorIndex()) : -1) & 0xFFFFFF;

            float[] pp = q.pos().clone();
            float[] uv = q.uv();
            int vc = pp.length / 3;
            float u0 = 0, du = 1, v0 = 0, dv = 1;
            if (sprite != null) {
                u0 = sprite.getU0(); du = sprite.getU1() - u0;
                v0 = sprite.getV0(); dv = sprite.getV1() - v0;
                if (du == 0) du = 1;
                if (dv == 0) dv = 1;
            }
            float[] t = new float[vc * 2];
            for (int i = 0; i < vc; i++) {
                t[i * 2] = (uv[i * 2] - u0) / du;
                t[i * 2 + 1] = 1f - (uv[i * 2 + 1] - v0) / dv;
            }
            String tex = sprite != null ? material(sprite, tint) : materialTex(q.texture(), tint);
            local.add(new PrintFace(pp, t, tex, block, isCulled(level, pos, st, pp)));
            sprites.add(sprite != null ? sprite.contents().name().toString() : q.texture().toString());
        }
        if (local.isEmpty()) return 0;

        List<PrintFace> done = (printMinBlocks > 0 || solidBlocks > 0) ? thicken(local) : local;
        int n = 0;
        for (PrintFace f : done) {
            if (f.culled()) continue;
            float[] p = f.p();
            for (int k = 0; k < p.length / 3; k++) {
                p[k * 3] += ox;
                p[k * 3 + 1] += oy;
                p[k * 3 + 2] += oz;
            }
            printFaces.add(f);
            n++;
        }
        return n;
    }

    /** Face encostada na borda do bloco e escondida pelo vizinho (mesma regra do culling do jogo). */
    private boolean isCulled(ClientLevel level, BlockPos pos, BlockState st, float[] p) {
        float[] n = QuadCollector.faceNormal(p);
        int vc = p.length / 3;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(n[a]) < 0.98f) continue;
            boolean lo = true, hi = true;
            for (int k = 0; k < vc; k++) {
                float c = p[k * 3 + a];
                if (Math.abs(c) > 1e-3f) lo = false;
                if (Math.abs(c - 1f) > 1e-3f) hi = false;
            }
            Direction d = null;
            if (lo && n[a] < 0) d = a == 0 ? Direction.WEST : a == 1 ? Direction.DOWN : Direction.NORTH;
            else if (hi && n[a] > 0) d = a == 0 ? Direction.EAST : a == 1 ? Direction.UP : Direction.SOUTH;
            if (d != null) return !Block.shouldRenderFace(st, level, pos, d, pos.relative(d));
        }
        return false;
    }

    private static String vkey(float[] p, int k) {
        return Math.round(p[k * 3] * 1000) + "," + Math.round(p[k * 3 + 1] * 1000) + "," + Math.round(p[k * 3 + 2] * 1000);
    }

    private static int axisOf(float[] n) {
        for (int a = 0; a < 3; a++) if (Math.abs(n[a]) > 0.98f) return a;
        return -1;
    }

    private static boolean onBoundary(float c) {
        return Math.abs(c) < 1e-3f || Math.abs(c - 1f) < 1e-3f;
    }

    /**
     * Espessura minima por bloco (coordenadas locais, 0..1), a partir da geometria do modelo:
     *  1) Plano solto (flor, trilho, escada de mao, cipo...: face sem nenhuma aresta compartilhada com face
     *     em outro angulo): vira um prisma, extrudado pra tras pela espessura minima.
     *  2) Fatia fina (duas faces paralelas, opostas e sobrepostas a menos da espessura minima: vidraca, grade,
     *     tocha, carpete...): as faces sao afastadas ate a espessura minima. Os vertices que estao naquele plano
     *     (laterais, faces vizinhas) acompanham, entao a malha continua fechada. Uma face encostada na borda do
     *     bloco nao se mexe: o outro lado cresce (o carpete sobe em vez de afundar no bloco de baixo).
     */
    private List<PrintFace> thicken(List<PrintFace> in) {
        final float tmin = printMinBlocks;
        final float EPS = 1e-3f;
        int n = in.size();

        float[][] nrm = new float[n][], lo = new float[n][], hi = new float[n][], cen = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] p = in.get(i).p();
            int vc = p.length / 3;
            nrm[i] = QuadCollector.faceNormal(p);
            lo[i] = new float[]{1e9f, 1e9f, 1e9f};
            hi[i] = new float[]{-1e9f, -1e9f, -1e9f};
            cen[i] = new float[3];
            for (int k = 0; k < vc; k++) for (int a = 0; a < 3; a++) {
                float c = p[k * 3 + a];
                lo[i][a] = Math.min(lo[i][a], c);
                hi[i][a] = Math.max(hi[i][a], c);
                cen[i][a] += c / vc;
            }
        }

        // faces "de casca": compartilham uma aresta com outra face em outro angulo
        Map<String, List<Integer>> edges = new HashMap<>();
        for (int i = 0; i < n; i++) {
            float[] p = in.get(i).p();
            int vc = p.length / 3;
            for (int k = 0; k < vc; k++) {
                String a = vkey(p, k), b = vkey(p, (k + 1) % vc);
                edges.computeIfAbsent(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, x -> new ArrayList<>(2)).add(i);
            }
        }
        boolean[] shell = new boolean[n];
        for (List<Integer> l : edges.values()) {
            for (int x : l) for (int y : l) {
                if (x == y) continue;
                float d = nrm[x][0] * nrm[y][0] + nrm[x][1] * nrm[y][1] + nrm[x][2] * nrm[y][2];
                if (Math.abs(d) < 0.99f) shell[x] = true;
            }
        }

        float[][] disp = new float[n][];
        for (int i = 0; i < n; i++) disp[i] = new float[in.get(i).p().length];
        boolean[] drop = new boolean[n];
        List<PrintFace> extra = new ArrayList<>();
        Set<String> loneSeen = new HashSet<>();
        Set<Long> donePairs = new HashSet<>();

        for (int i = 0; i < n; i++) {
            if (!shell[i]) {
                // plano solto: um prisma por plano (gemeos coincidentes, de dupla face, viram um so)
                if (solidBlocks > 0) {
                    // solidify: so os texels opacos viram caixa (silhueta do pixel art preservada)
                    if (!in.get(i).culled() && loneSeen.add(faceKey(in.get(i).p())))
                        extra.addAll(solidLone(in.get(i), nrm[i], cen[i]));
                } else if (tmin > 0) {
                    if (loneSeen.add(faceKey(in.get(i).p()))) extra.addAll(extrude(in.get(i), nrm[i], tmin));
                } else {
                    continue;
                }
                drop[i] = true;
                continue;
            }
            int a = axisOf(nrm[i]);
            if (a < 0) continue;                                  // fatias inclinadas: nao tratadas
            float sgn = Math.signum(nrm[i][a]);

            int best = -1;
            float bestD = tmin;
            for (int j = 0; j < n; j++) {
                if (j == i || !shell[j] || axisOf(nrm[j]) != a || nrm[j][a] * sgn >= 0) continue;
                float d = sgn * (cen[i][a] - cen[j][a]);          // distancia ate a face oposta, atras desta
                if (d <= EPS || d >= bestD - EPS) continue;
                boolean overlap = true;
                for (int b = 0; b < 3; b++) {
                    if (b == a) continue;
                    if (!(lo[i][b] < hi[j][b] - EPS && lo[j][b] < hi[i][b] - EPS)) overlap = false;
                }
                if (!overlap) continue;
                best = j;
                bestD = d;
            }
            if (best < 0) continue;
            if (!donePairs.add(((long) Math.min(i, best) << 32) | Math.max(i, best))) continue;

            float total = tmin - bestD;
            boolean bi = onBoundary(cen[i][a]), bj = onBoundary(cen[best][a]);
            float pushF = (bi && !bj) ? 0f : (bj && !bi) ? total : total / 2f;
            float pushB = total - pushF;
            moveVertices(in, disp, a, cen[i][a], lo[i], hi[i], sgn * pushF, EPS);
            moveVertices(in, disp, a, cen[best][a], lo[best], hi[best], -sgn * pushB, EPS);
        }

        List<PrintFace> out = new ArrayList<>(n + extra.size());
        for (int i = 0; i < n; i++) {
            if (drop[i]) continue;
            float[] p = in.get(i).p();
            for (int k = 0; k < p.length; k++) p[k] += disp[i][k];
            out.add(in.get(i));
        }
        out.addAll(extra);
        return out;
    }

    /** Desloca (em disp) os vertices de todas as faces que estao no plano e dentro da area da face fina. */
    private static void moveVertices(List<PrintFace> in, float[][] disp, int axis, float plane,
                                     float[] lo, float[] hi, float delta, float eps) {
        if (delta == 0f) return;
        for (int q = 0; q < in.size(); q++) {
            float[] p = in.get(q).p();
            for (int k = 0; k < p.length / 3; k++) {
                if (Math.abs(p[k * 3 + axis] - plane) > eps) continue;
                boolean inside = true;
                for (int b = 0; b < 3; b++) {
                    if (b == axis) continue;
                    if (p[k * 3 + b] < lo[b] - eps || p[k * 3 + b] > hi[b] + eps) inside = false;
                }
                if (inside && Math.abs(delta) > Math.abs(disp[q][k * 3 + axis])) disp[q][k * 3 + axis] = delta;
            }
        }
    }

    private static final int MAX_DIV = 128;

    /**
     * Solidify de um plano solto: cada texel opaco vira uma caixa quadrada (silhueta do pixel art mantida).
     * Plano encostado na borda do bloco cresce pra dentro do bloco; os demais crescem pros dois lados.
     * Plano 100% opaco (sem nenhum texel transparente) vira uma laje simples, bem mais leve.
     */
    private List<PrintFace> solidLone(PrintFace f, float[] n, float[] cen) {
        float depth = solidBlocks, fo, bo;
        int a = axisOf(n);
        if (a >= 0 && onBoundary(cen[a])) {
            float outward = cen[a] > 0.5f ? 1f : -1f;
            if (n[a] * outward > 0) { fo = 0f; bo = depth; }      // olha pra fora: cresce pra dentro
            else { fo = depth; bo = 0f; }                          // olha pra dentro: a frente avanca
        } else {
            fo = bo = depth / 2f;
        }

        final NativeImage im = printImages.get(f.tex());
        List<PixelTools.Cell> cells = null;
        if (im != null && f.p().length == 12 && f.t().length == 8) {
            cells = PixelTools.solidPlane(f.p(), f.t(), im.getWidth(), im.getHeight(),
                (x, y) -> im.getPixelRGBA(x, y) >>> 24, n, fo, bo, MAX_DIV);
        }
        List<PrintFace> r = new ArrayList<>();
        if (cells == null) {
            r = extrude(f, n, depth);
            if (fo != 0f) {
                for (PrintFace q : r) {
                    float[] p = q.p();
                    for (int k = 0; k < p.length / 3; k++) for (int c = 0; c < 3; c++) p[k * 3 + c] += n[c] * fo;
                }
            }
            return r;
        }
        for (PixelTools.Cell c : cells) r.add(new PrintFace(c.p(), c.t(), f.tex(), f.block(), false));
        return r;
    }

    /** Extruda uma face pra tras (contra a normal): devolve frente + fundo + laterais, todos virados pra fora. */
    private List<PrintFace> extrude(PrintFace f, float[] n, float tmin) {
        float[] p = f.p(), t = f.t();
        int m = p.length / 3;
        float[] bp = new float[m * 3], bt = new float[m * 2];
        for (int k = 0; k < m; k++) {
            int j = m - 1 - k;                                    // ordem invertida: o fundo olha pra tras
            for (int a = 0; a < 3; a++) bp[k * 3 + a] = p[j * 3 + a] - n[a] * tmin;
            bt[k * 2] = t[j * 2];
            bt[k * 2 + 1] = t[j * 2 + 1];
        }
        List<PrintFace> r = new ArrayList<>();
        r.add(f);
        r.add(new PrintFace(bp, bt, f.tex(), f.block(), false));

        float[] c = new float[3];
        for (int k = 0; k < m; k++) for (int a = 0; a < 3; a++) c[a] += p[k * 3 + a] / m;
        for (int a = 0; a < 3; a++) c[a] -= n[a] * tmin / 2f;     // centro do prisma

        for (int k = 0; k < m; k++) {
            int k2 = (k + 1) % m;
            float[] sp = new float[12];
            float[] st = new float[8];
            for (int a = 0; a < 3; a++) {
                sp[a] = p[k * 3 + a];
                sp[3 + a] = p[k * 3 + a] - n[a] * tmin;
                sp[6 + a] = p[k2 * 3 + a] - n[a] * tmin;
                sp[9 + a] = p[k2 * 3 + a];
            }
            // a lateral usa a cor da borda da face (UV colapsada nela)
            st[0] = t[k * 2];  st[1] = t[k * 2 + 1];
            st[2] = t[k * 2];  st[3] = t[k * 2 + 1];
            st[4] = t[k2 * 2]; st[5] = t[k2 * 2 + 1];
            st[6] = t[k2 * 2]; st[7] = t[k2 * 2 + 1];

            float[] sn = QuadCollector.faceNormal(sp);
            float mx = (sp[0] + sp[3] + sp[6] + sp[9]) / 4f - c[0];
            float my = (sp[1] + sp[4] + sp[7] + sp[10]) / 4f - c[1];
            float mz = (sp[2] + sp[5] + sp[8] + sp[11]) / 4f - c[2];
            if (sn[0] * mx + sn[1] * my + sn[2] * mz < 0) {       // virada pra dentro: inverte
                for (int a = 0; a < 3; a++) {
                    float tmp = sp[a]; sp[a] = sp[9 + a]; sp[9 + a] = tmp;
                    tmp = sp[3 + a]; sp[3 + a] = sp[6 + a]; sp[6 + a] = tmp;
                }
                float u = st[0], v = st[1]; st[0] = st[6]; st[1] = st[7]; st[6] = u; st[7] = v;
                u = st[2]; v = st[3]; st[2] = st[4]; st[3] = st[5]; st[4] = u; st[5] = v;
            }
            r.add(new PrintFace(sp, st, f.tex(), f.block(), false));
        }
        return r;
    }

    /** Copia da imagem ja multiplicada pelo tint (fica guardada ate montar o atlas). */
    private NativeImage tintedCopy(NativeImage base, int tint) {
        int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
        NativeImage out = new NativeImage(base.getWidth(), base.getHeight(), false);
        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                int px = base.getPixelRGBA(x, y);              // ABGR
                int a = px >>> 24, b = (px >> 16) & 255, g = (px >> 8) & 255, r = px & 255;
                out.setPixelRGBA(x, y, (a << 24) | ((b * tb / 255) << 16) | ((g * tg / 255) << 8) | (r * tr / 255));
            }
        }
        return out;
    }

    private static String faceKey(float[] p) {
        int n = p.length / 3;
        String[] v = new String[n];
        for (int i = 0; i < n; i++)
            v[i] = Math.round(p[i * 3] * 1000) + "," + Math.round(p[i * 3 + 1] * 1000) + "," + Math.round(p[i * 3 + 2] * 1000);
        Arrays.sort(v);
        return String.join("|", v);
    }

    /**
     * Limpeza entre blocos:
     *  - faces coincidentes na mesma direcao (ex.: overlay da lateral da grama) viram UMA face, com as texturas compostas;
     *  - faces coincidentes em direcoes opostas e de blocos diferentes (parede entre dois blocos encostados) somem juntas.
     */
    private List<PrintFace> removeInternalFaces() {
        Map<String, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < printFaces.size(); i++)
            groups.computeIfAbsent(faceKey(printFaces.get(i).p()), k -> new ArrayList<>(2)).add(i);

        PrintFace[] cur = printFaces.toArray(new PrintFace[0]);
        boolean[] drop = new boolean[cur.length];
        for (List<Integer> g : groups.values()) {
            if (g.size() < 2) continue;
            float[] n0 = QuadCollector.faceNormal(cur[g.get(0)].p());
            List<Integer> same = new ArrayList<>(), opposite = new ArrayList<>();
            for (int i : g) {
                float[] n = QuadCollector.faceNormal(cur[i].p());
                (n[0] * n0[0] + n[1] * n0[1] + n[2] * n0[2] >= 0 ? same : opposite).add(i);
            }
            int sBase = mergeDuplicates(cur, drop, same);
            int oBase = mergeDuplicates(cur, drop, opposite);
            if (sBase >= 0 && oBase >= 0 && cur[sBase].block() != cur[oBase].block()) {
                drop[sBase] = true;
                drop[oBase] = true;
            }
        }
        List<PrintFace> kept = new ArrayList<>(cur.length);
        for (int i = 0; i < cur.length; i++) {
            if (drop[i]) printRemoved++; else kept.add(cur[i]);
        }
        return kept;
    }

    /** Junta duplicatas coincidentes (mesma direcao) na primeira; devolve o indice que sobrevive, ou -1. */
    private int mergeDuplicates(PrintFace[] cur, boolean[] drop, List<Integer> list) {
        if (list.isEmpty()) return -1;
        int base = list.get(0);
        for (int k = 1; k < list.size(); k++) {
            int j = list.get(k);
            String merged = compositeTex(cur[base].tex(), cur[j].tex());
            cur[base] = new PrintFace(cur[base].p(), cur[base].t(), merged, cur[base].block(), false);
            drop[j] = true;
        }
        return base;
    }

    /** Textura "top" (com alpha) desenhada por cima de "base": resolve overlays como a lateral da grama. */
    private String compositeTex(String baseKey, String topKey) {
        if (baseKey.equals(topKey)) return baseKey;
        String key = baseKey + "+" + topKey;
        if (!printImages.containsKey(key)) {
            NativeImage b = printImages.get(baseKey), t = printImages.get(topKey);
            NativeImage out = new NativeImage(b.getWidth(), b.getHeight(), false);
            for (int y = 0; y < b.getHeight(); y++) {
                for (int x = 0; x < b.getWidth(); x++) {
                    int bp = b.getPixelRGBA(x, y);
                    int tp = t.getPixelRGBA(x * t.getWidth() / b.getWidth(), y * t.getHeight() / b.getHeight());
                    int ta = tp >>> 24;
                    int res;
                    if (ta == 0) res = bp;
                    else if (ta == 255) res = tp;
                    else {
                        int ba = bp >>> 24;
                        int r = ((tp & 255) * ta + (bp & 255) * (255 - ta)) / 255;
                        int g = (((tp >> 8) & 255) * ta + ((bp >> 8) & 255) * (255 - ta)) / 255;
                        int bl = (((tp >> 16) & 255) * ta + ((bp >> 16) & 255) * (255 - ta)) / 255;
                        int al = ta + ba * (255 - ta) / 255;
                        res = (al << 24) | (bl << 16) | (g << 8) | r;
                    }
                    out.setPixelRGBA(x, y, res);
                }
            }
            printImages.put(key, out);
        }
        return key;
    }

    /** Composita pixels transparentes sobre cinza claro (impressoras coloridas ignoram alpha). */
    private static int flatten(int px) {
        int a = px >>> 24;
        if (a == 255) return px;
        int b = (px >> 16) & 255, g = (px >> 8) & 255, r = px & 255, bg = 220;
        r = (r * a + bg * (255 - a)) / 255;
        g = (g * a + bg * (255 - a)) / 255;
        b = (b * a + bg * (255 - a)) / 255;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    /** Monta model_print.obj (1 objeto, vertices compartilhados), model_print.mtl (1 material) e atlas.png. */
    private void writePrint() throws IOException {
        try {
            if (printImages.isEmpty() || printFaces.isEmpty()) throw new IOException("Nenhuma face pra exportar.");
            List<PrintFace> faces = removeInternalFaces();
            if (pixels) {
                writePixels(faces);
                return;
            }

            // --- atlas: empacotamento em prateleiras, com borda de 2px replicada (evita sangrar no filtro) ---
            List<String> names = new ArrayList<>(printImages.keySet());
            names.sort((x, y) -> {
                NativeImage ia = printImages.get(x), ib = printImages.get(y);
                int c = Integer.compare(ib.getHeight(), ia.getHeight());
                return c != 0 ? c : Integer.compare(ib.getWidth(), ia.getWidth());
            });
            Map<String, int[]> pos = new HashMap<>();
            int aw = 0, ah = 0;
            for (int w = 256; w <= ATLAS_MAX && aw == 0; w *= 2) {
                pos.clear();
                int x = 0, y = 0, rowH = 0;
                boolean ok = true;
                for (String nm : names) {
                    NativeImage im = printImages.get(nm);
                    int tw = im.getWidth() + 2 * ATLAS_PAD, th = im.getHeight() + 2 * ATLAS_PAD;
                    if (tw > w) { ok = false; break; }
                    if (x + tw > w) { x = 0; y += rowH; rowH = 0; }
                    pos.put(nm, new int[]{x + ATLAS_PAD, y + ATLAS_PAD});
                    x += tw;
                    rowH = Math.max(rowH, th);
                }
                if (ok && y + rowH <= w) { aw = w; ah = y + rowH; }
            }
            if (aw == 0) throw new IOException("Texturas demais pra caber num atlas de " + ATLAS_MAX + "x" + ATLAS_MAX + ". Exporte uma area menor.");
            ah = Integer.highestOneBit(Math.max(ah, 2) - 1) << 1;      // proxima potencia de 2

            try (NativeImage atlas = new NativeImage(aw, ah, true)) {
                for (String nm : names) {
                    NativeImage im = printImages.get(nm);
                    int[] o = pos.get(nm);
                    int w = im.getWidth(), h = im.getHeight();
                    for (int yy = -ATLAS_PAD; yy < h + ATLAS_PAD; yy++) {
                        for (int xx = -ATLAS_PAD; xx < w + ATLAS_PAD; xx++) {
                            int sx = Math.min(Math.max(xx, 0), w - 1), sy = Math.min(Math.max(yy, 0), h - 1);
                            atlas.setPixelRGBA(o[0] + xx, o[1] + yy, flatten(im.getPixelRGBA(sx, sy)));
                        }
                    }
                }
                atlas.writeToFile(dir.resolve("atlas.png"));
            }

            // --- OBJ: vertices compartilhados por posicao (malha conectada), UV remapeada pro atlas ---
            Map<K3, Integer> vmap = new HashMap<>();
            Map<K2, Integer> tmap = new HashMap<>();
            StringBuilder vs = new StringBuilder(), ts = new StringBuilder(), fs = new StringBuilder();
            int vc = 0, tc = 0;

            for (PrintFace face : faces) {
                NativeImage im = printImages.get(face.tex());
                int[] o = pos.get(face.tex());
                int n = face.p().length / 3;
                int[] vi = new int[n], ti = new int[n];
                for (int k = 0; k < n; k++) {
                    float x = face.p()[k * 3], y = face.p()[k * 3 + 1], z = face.p()[k * 3 + 2];
                    K3 key = new K3(Math.round(x * 1000), Math.round(y * 1000), Math.round(z * 1000));
                    Integer idx = vmap.get(key);
                    if (idx == null) {
                        idx = ++vc;
                        vmap.put(key, idx);
                        vs.append("v ").append(f(x * printScale)).append(' ').append(f(y * printScale)).append(' ').append(f(z * printScale)).append('\n');
                    }
                    vi[k] = idx;

                    float ul = Math.min(1f, Math.max(0f, face.t()[k * 2]));
                    float vl = Math.min(1f, Math.max(0f, 1f - face.t()[k * 2 + 1]));   // V local, de cima pra baixo
                    float u = (o[0] + ul * im.getWidth()) / aw;
                    float v = 1f - (o[1] + vl * im.getHeight()) / ah;
                    K2 tk = new K2(Math.round(u * 100000), Math.round(v * 100000));
                    Integer tidx = tmap.get(tk);
                    if (tidx == null) {
                        tidx = ++tc;
                        tmap.put(tk, tidx);
                        ts.append("vt ").append(f(u)).append(' ').append(f(v)).append('\n');
                    }
                    ti[k] = tidx;
                }
                // descarta face degenerada (vertices coincidentes depois de juntar)
                int distinct = 0;
                for (int a = 0; a < n; a++) {
                    boolean dup = false;
                    for (int b = 0; b < a; b++) if (vi[a] == vi[b]) { dup = true; break; }
                    if (!dup) distinct++;
                }
                if (distinct < 3) continue;
                fs.append('f');
                for (int k = 0; k < n; k++) fs.append(' ').append(vi[k]).append('/').append(ti[k]);
                fs.append('\n');
            }

            writePrintFiles(vs, ts, fs);
        } finally {
            printImages.values().forEach(NativeImage::close);
        }
    }

    private void writePrintFiles(StringBuilder vs, StringBuilder ts, StringBuilder fs) throws IOException {
        Files.writeString(dir.resolve("model_print.obj"),
            "# Block Export - modo impressao 3D. Unidade: milimetros (1 bloco = " + printScale + " mm), Y para cima\n"
                + "mtllib model_print.mtl\no minecraft_build\n"
                + vs + ts + "usemtl atlas\n" + fs);
        Files.writeString(dir.resolve("model_print.mtl"),
            "newmtl atlas\nKa 1 1 1\nKd 1 1 1\nKs 0 0 0\nd 1\nillum 1\nmap_Kd atlas.png\n");
    }

    // ------------------------------------------------------------------ modo pixels (1 texel = 1 face = 1 pixel no atlas)

    private static final int PIXEL_MAX_CELLS = 3_000_000, POOL_W = 128;

    private static final class PxFace {
        PixelTools.Grid g;
        NativeImage im;
        int minX, minY, bw, bh;     // caixa de texels usada pela face
        boolean pool;               // caixa 1x1: o pixel vem do "pool" (evita milhares de ladrilhos 1x1)
        int poolIdx;
        int ox, oy;                 // tile: origem no atlas; pool: posicao do proprio pixel no atlas
    }

    private static float[] pad4(float[] a, int per) {
        if (a.length >= 4 * per) return a;
        float[] r = new float[4 * per];
        System.arraycopy(a, 0, r, 0, a.length);
        for (int c = 0; c < per; c++) r[(a.length / per) * per + c] = a[a.length - per + c];   // triangulo: repete o ultimo vertice
        return r;
    }

    /**
     * Cada texel (nao totalmente transparente) de cada face vira uma face propria, com um pixel UNICO no atlas.
     * Cada face original ganha um ladrilho com a mesma orientacao da textura do jogo, entao da pra pintar o atlas.png
     * em 2D reconhecendo o desenho; ou pintar direto no modelo (UV de cada face = o quadrado do proprio pixel).
     */
    private void writePixels(List<PrintFace> faces) throws IOException {
        if (printImages.isEmpty() || faces.isEmpty()) throw new IOException("Nenhuma face pra exportar.");

        // --- passada 1: grade de cada face, caixa de texels usada, contagem
        List<PxFace> list = new ArrayList<>();
        long cells = 0;
        int poolCount = 0;
        for (PrintFace face : faces) {
            NativeImage im = printImages.get(face.tex());
            PixelTools.Grid g = new PixelTools.Grid(pad4(face.p(), 3), pad4(face.t(), 2), im.getWidth(), im.getHeight(), MAX_DIV);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, cnt = 0;
            for (int i = 0; i < g.ns; i++) {
                for (int j = 0; j < g.nt; j++) {
                    int[] t = g.texel(i, j);
                    if ((im.getPixelRGBA(t[0], t[1]) >>> 24) == 0) continue;
                    cnt++;
                    minX = Math.min(minX, t[0]); maxX = Math.max(maxX, t[0]);
                    minY = Math.min(minY, t[1]); maxY = Math.max(maxY, t[1]);
                }
            }
            if (cnt == 0) continue;
            cells += cnt;
            if (cells > PIXEL_MAX_CELLS)
                throw new IOException("Faces demais pro modo pixels (mais de " + PIXEL_MAX_CELLS + "). Exporte uma area menor.");
            PxFace pf = new PxFace();
            pf.g = g; pf.im = im;
            pf.minX = minX; pf.minY = minY; pf.bw = maxX - minX + 1; pf.bh = maxY - minY + 1;
            pf.pool = pf.bw == 1 && pf.bh == 1;
            if (pf.pool) pf.poolIdx = poolCount++;
            list.add(pf);
        }
        if (list.isEmpty()) throw new IOException("Nenhuma face opaca pra exportar.");

        // --- atlas: ladrilhos (e o pool) empacotados em prateleiras, sem margem (cada face usa o quadrado exato do pixel)
        List<PxFace> tiles = new ArrayList<>();
        for (PxFace pf : list) if (!pf.pool) tiles.add(pf);
        int pw = Math.max(1, Math.min(POOL_W, poolCount));
        int ph = poolCount == 0 ? 0 : (poolCount + pw - 1) / pw;
        int n = tiles.size() + 1;                              // item 0 = pool
        int[] iw = new int[n], ih = new int[n];
        iw[0] = poolCount > 0 ? pw : 0;
        ih[0] = ph;
        for (int k = 0; k < tiles.size(); k++) { iw[k + 1] = tiles.get(k).bw; ih[k + 1] = tiles.get(k).bh; }
        Integer[] ord = new Integer[n];
        for (int k = 0; k < n; k++) ord[k] = k;
        Arrays.sort(ord, (x, y) -> {
            int c = Integer.compare(ih[y], ih[x]);
            return c != 0 ? c : Integer.compare(iw[y], iw[x]);
        });
        int[] px = new int[n], py = new int[n];
        int aw = 0, ah = 0;
        for (int w = 256; w <= ATLAS_MAX && aw == 0; w *= 2) {
            int x = 0, y = 0, rowH = 0;
            boolean ok = true;
            for (int idx : ord) {
                if (iw[idx] == 0) continue;
                if (iw[idx] > w) { ok = false; break; }
                if (x + iw[idx] > w) { x = 0; y += rowH; rowH = 0; }
                px[idx] = x; py[idx] = y;
                x += iw[idx];
                rowH = Math.max(rowH, ih[idx]);
            }
            if (ok && y + rowH <= w) { aw = w; ah = y + rowH; }
        }
        if (aw == 0) throw new IOException("Texturas demais pra caber num atlas de " + ATLAS_MAX + "x" + ATLAS_MAX + ". Exporte uma area menor.");
        ah = Integer.highestOneBit(Math.max(ah, 2) - 1) << 1;

        try (NativeImage atlas = new NativeImage(aw, ah, true)) {
            for (int k = 0; k < tiles.size(); k++) {
                PxFace pf = tiles.get(k);
                pf.ox = px[k + 1];
                pf.oy = py[k + 1];
                for (int y = 0; y < pf.bh; y++)
                    for (int x = 0; x < pf.bw; x++)
                        atlas.setPixelRGBA(pf.ox + x, pf.oy + y, flatten(pf.im.getPixelRGBA(pf.minX + x, pf.minY + y)));
            }
            for (PxFace pf : list) {
                if (!pf.pool) continue;
                pf.ox = px[0] + pf.poolIdx % pw;
                pf.oy = py[0] + pf.poolIdx / pw;
                atlas.setPixelRGBA(pf.ox, pf.oy, flatten(pf.im.getPixelRGBA(pf.minX, pf.minY)));
            }
            atlas.writeToFile(dir.resolve("atlas.png"));
        }

        // --- passada 2: uma face por celula; UV = quadrado do pixel (orientado como a textura original)
        Map<K3, Integer> vmap = new HashMap<>();
        Map<K2, Integer> tmap = new HashMap<>();
        StringBuilder vs = new StringBuilder(), ts = new StringBuilder(), fs = new StringBuilder();
        int vc = 0, tc = 0;
        float[] pos = new float[3], cu = new float[2], cuv = new float[2];
        int[] vi = new int[4], ti = new int[4];

        for (PxFace pf : list) {
            PixelTools.Grid g = pf.g;
            for (int i = 0; i < g.ns; i++) {
                for (int j = 0; j < g.nt; j++) {
                    int[] tx = g.texel(i, j);
                    if ((pf.im.getPixelRGBA(tx[0], tx[1]) >>> 24) == 0) continue;
                    int baseX = pf.pool ? pf.ox : pf.ox + (tx[0] - pf.minX);
                    int baseY = pf.pool ? pf.oy : pf.oy + (tx[1] - pf.minY);
                    g.tex((i + 0.5f) / g.ns, (j + 0.5f) / g.nt, cu, 0);

                    for (int k = 0; k < 4; k++) {
                        int ci = (k == 1 || k == 2) ? 1 : 0, cj = k >= 2 ? 1 : 0;
                        float s = (i + ci) / (float) g.ns, tt = (j + cj) / (float) g.nt;
                        g.pos(s, tt, pos, 0);
                        K3 key = new K3(Math.round(pos[0] * 1000), Math.round(pos[1] * 1000), Math.round(pos[2] * 1000));
                        Integer idx = vmap.get(key);
                        if (idx == null) {
                            idx = ++vc;
                            vmap.put(key, idx);
                            vs.append("v ").append(f(pos[0] * printScale)).append(' ').append(f(pos[1] * printScale)).append(' ').append(f(pos[2] * printScale)).append('\n');
                        }
                        vi[k] = idx;

                        int bu, bv;
                        if (g.collapsed) {
                            bu = ci;
                            bv = cj;
                        } else {
                            g.tex(s, tt, cuv, 0);
                            bu = cuv[0] > cu[0] + 1e-4f ? 1 : cuv[0] < cu[0] - 1e-4f ? 0 : ci;
                            bv = cuv[1] > cu[1] + 1e-4f ? 1 : cuv[1] < cu[1] - 1e-4f ? 0 : cj;
                        }
                        float u = (baseX + bu) / (float) aw;
                        float v = 1f - (baseY + bv) / (float) ah;
                        K2 tk = new K2(Math.round(u * 100000), Math.round(v * 100000));
                        Integer tidx = tmap.get(tk);
                        if (tidx == null) {
                            tidx = ++tc;
                            tmap.put(tk, tidx);
                            ts.append("vt ").append(f(u)).append(' ').append(f(v)).append('\n');
                        }
                        ti[k] = tidx;
                    }
                    int distinct = 0;
                    for (int a = 0; a < 4; a++) {
                        boolean dup = false;
                        for (int b = 0; b < a; b++) if (vi[a] == vi[b]) { dup = true; break; }
                        if (!dup) distinct++;
                    }
                    if (distinct < 3) continue;
                    fs.append('f');
                    for (int k = 0; k < 4; k++) fs.append(' ').append(vi[k]).append('/').append(ti[k]);
                    fs.append('\n');
                    pixelFaceCount++;
                }
            }
        }
        writePrintFiles(vs, ts, fs);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }

    // ------------------------------------------------------------------ materiais / texturas

    /** Material para um sprite do atlas de blocos (modelos e partial models). */
    private String material(TextureAtlasSprite sprite, int tint) throws IOException {
        ResourceLocation name = sprite.contents().name();
        NativeImage base = spriteCache.computeIfAbsent(name, k -> crop(sprite));
        boolean alpha = spriteAlpha.getOrDefault(name, false);
        return registerMaterial(safe(name.toString()), base, alpha, tint);
    }

    /** Material para uma textura inteira (entidades, baus, camas, placas, atlas de sheets...). */
    private String materialTex(ResourceLocation loc, int tint) throws IOException {
        TexImg ti = texImages.get(loc);
        if (ti == null) {
            ti = downloadTexture(loc);
            if (ti == null) throw new IOException("textura vazia: " + loc);
            texImages.put(loc, ti);
        }
        return registerMaterial("tex_" + safe(loc.toString()), ti.img(), ti.alpha(), tint);
    }

    private String registerMaterial(String baseName, NativeImage base, boolean alpha, int tint) throws IOException {
        String texName = tint == 0xFFFFFF ? baseName : baseName + "_" + String.format("%06x", tint);
        if (print) {
            printImages.computeIfAbsent(texName, k -> tintedCopy(base, tint));
            return texName;
        }
        String mat = texName + (alpha ? "_a" : "");

        if (!mtl.containsKey(mat)) {
            if (texDone.add(texName)) writeTexture(base, texName, tint);
            StringBuilder sb = new StringBuilder();
            sb.append("newmtl ").append(mat).append("\n")
              .append("Ka 1 1 1\nKd 1 1 1\nKs 0 0 0\nd 1\nillum 1\n")
              .append("map_Kd textures/").append(texName).append(".png\n");
            if (alpha) sb.append("map_d textures/").append(texName).append(".png\n");
            sb.append("\n");
            mtl.put(mat, sb.toString());
        }
        return mat;
    }

    /** Baixa o atlas de blocos da GPU uma vez; assim pegamos tambem sprites gerados em runtime. */
    private void captureAtlas() {
        TextureAtlas atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        RenderSystem.bindTexture(atlas.getId());
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        atlasImg = new NativeImage(w, h, false);
        atlasImg.downloadTexture(0, false);
    }

    /** Baixa qualquer textura registrada (png de entidade, atlas de sheets, ...) direto da GPU. */
    private TexImg downloadTexture(ResourceLocation loc) {
        AbstractTexture tex = mc.getTextureManager().getTexture(loc);
        RenderSystem.bindTexture(tex.getId());
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) return null;
        NativeImage img = new NativeImage(w, h, false);
        img.downloadTexture(0, false);
        boolean alpha = false;
        for (int y = 0; y < h && !alpha; y++)
            for (int x = 0; x < w; x++)
                if ((img.getPixelRGBA(x, y) >>> 24) != 255) { alpha = true; break; }
        return new TexImg(img, alpha);
    }

    private NativeImage crop(TextureAtlasSprite s) {
        int w = s.contents().width(), h = s.contents().height();
        NativeImage out = new NativeImage(w, h, false);
        boolean alpha = false;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = atlasImg.getPixelRGBA(s.getX() + x, s.getY() + y);
                if ((px >>> 24) != 255) alpha = true;
                out.setPixelRGBA(x, y, px);
            }
        }
        spriteAlpha.put(s.contents().name(), alpha);
        return out;
    }

    private void writeTexture(NativeImage base, String texName, int tint) throws IOException {
        Path out = dir.resolve("textures").resolve(texName + ".png");
        if (tint == 0xFFFFFF) {
            base.writeToFile(out);
            return;
        }
        int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
        try (NativeImage tinted = new NativeImage(base.getWidth(), base.getHeight(), false)) {
            for (int y = 0; y < base.getHeight(); y++) {
                for (int x = 0; x < base.getWidth(); x++) {
                    int px = base.getPixelRGBA(x, y);          // ABGR
                    int a = px >>> 24, b = (px >> 16) & 255, g = (px >> 8) & 255, r = px & 255;
                    tinted.setPixelRGBA(x, y, (a << 24) | ((b * tb / 255) << 16) | ((g * tg / 255) << 8) | (r * tr / 255));
                }
            }
            tinted.writeToFile(out);
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
