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

    private BufferedWriter wv, wvt, wvn, wf;
    private int vi = 1, ni = 1, quadCount = 0;
    private String curMat;

    public ModelExporter(Path dir, boolean cull, boolean ber) {
        this.dir = dir;
        this.cull = cull;
        this.ber = ber;
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

        // JSON
        JsonObject root = new JsonObject();
        JsonArray origin = new JsonArray();
        origin.add(min.getX()); origin.add(min.getY()); origin.add(min.getZ());
        root.add("originWorldPos", origin);
        root.addProperty("cull", cull);
        root.addProperty("blockEntityRenderers", ber);
        JsonObject sk = new JsonObject();
        skipped.forEach(sk::addProperty);
        root.add("skipped", sk);
        root.add("errors", errorsJson);
        root.add("blocks", blocksJson);
        Files.writeString(dir.resolve("blocks.json"), GSON.toJson(root));

        return new Result(blocks, quadCount, mtl.size(), skipped);
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

        for (QuadCollector.Quad q : quads) {
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
            capture.getBuffer(RenderType.solid()), cull, RandomSource.create(st.getSeed(pos)));
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
