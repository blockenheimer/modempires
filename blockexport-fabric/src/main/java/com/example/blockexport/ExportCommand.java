package com.example.blockexport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * /blockexport pos1 | pos2                       -> marca cantos com a posicao dos seus pes
 * /blockexport selection [cull]                  -> exporta o que esta entre pos1 e pos2
 * /blockexport radius <r> [cull]                 -> exporta cubo ao redor do jogador
 * /blockexport box x1 y1 z1 x2 y2 z2 [cull]      -> exporta caixa por coordenadas
 * /blockexport ber <true|false>                  -> liga/desliga captura de BlockEntityRenderers (padrao: ligado)
 * /blockexport print <true|false>                -> modo impressao 3D: 1 objeto, 1 textura (atlas), sem faces internas
 * /blockexport printsize <mmPorBloco> <minMm>    -> escala (mm por bloco) e espessura minima das pecas finas (0 = desligado)
 * /blockexport solid <true|false> [pixels]        -> "solidify": planos finos (flor, escada, trilho...) ganham espessura (em pixels de
 *                                                    textura, padrao 3) mantendo a silhueta quadrada do pixel art. Usa a malha unica.
 * /blockexport pixels <true|false>                -> cada texel vira uma face com pixel proprio no atlas.png (pintavel fora do jogo).
 *                                                    Usa a malha unica (model_print.obj).
 *
 * cull = true (padrao) remove faces encobertas; false mantem todas as faces.
 */
public final class ExportCommand {
    private static final long MAX_VOLUME = 20_000_000L;
    private static BlockPos pos1, pos2;
    private static boolean ber = true;
    private static boolean print = false;
    private static float printMm = 10f;       // mm por bloco
    private static float printMin = 2f;       // espessura minima em mm (0 = desligado)
    private static float solidPx = 0f;        // solidify: espessura em pixels de textura (0 = desligado)
    private static boolean pixels = false;    // 1 texel = 1 face = 1 pixel do atlas

    private static RequiredArgumentBuilder<FabricClientCommandSource, Integer> i(String name) {
        return ClientCommandManager.argument(name, IntegerArgumentType.integer(-30_000_000, 30_000_000));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("blockexport")
            .then(ClientCommandManager.literal("pos1").executes(c -> {
                pos1 = player().blockPosition();
                return msg(c, "pos1 = " + pos1.toShortString());
            }))
            .then(ClientCommandManager.literal("pos2").executes(c -> {
                pos2 = player().blockPosition();
                return msg(c, "pos2 = " + pos2.toShortString());
            }))
            .then(ClientCommandManager.literal("ber")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
                    ber = BoolArgumentType.getBool(c, "on");
                    return msg(c, "BlockEntityRenderers " + (ber ? "ligados" : "desligados")
                        + (ber ? " (se usa Create, desligue o Flywheel antes: /flywheel backend off)" : ""));
                })))
            .then(ClientCommandManager.literal("print")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
                    print = BoolArgumentType.getBool(c, "on");
                    return msg(c, print
                        ? "Modo impressao 3D LIGADO (1 bloco = " + printMm + " mm, espessura minima " + printMin + " mm). Mude com /blockexport printsize."
                        : "Modo impressao 3D desligado (1 objeto por bloco, 1 textura por sprite).");
                })))
            .then(ClientCommandManager.literal("printsize")
                .then(ClientCommandManager.argument("mmPorBloco", FloatArgumentType.floatArg(0.5f, 1000f))
                    .then(ClientCommandManager.argument("minMm", FloatArgumentType.floatArg(0f, 50f)).executes(c -> {
                        printMm = FloatArgumentType.getFloat(c, "mmPorBloco");
                        printMin = FloatArgumentType.getFloat(c, "minMm");
                        return msg(c, "Impressao: 1 bloco = " + printMm + " mm; espessura minima = "
                            + (printMin > 0 ? printMin + " mm (" + (printMin / printMm) + " bloco)" : "desligada"));
                    }))))
            .then(ClientCommandManager.literal("solid")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool())
                    .executes(c -> solid(c, BoolArgumentType.getBool(c, "on") ? 3f : 0f))
                    .then(ClientCommandManager.argument("pixels", FloatArgumentType.floatArg(0.5f, 16f))
                        .executes(c -> solid(c, BoolArgumentType.getBool(c, "on") ? FloatArgumentType.getFloat(c, "pixels") : 0f)))))
            .then(ClientCommandManager.literal("pixels")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(c -> {
                    pixels = BoolArgumentType.getBool(c, "on");
                    return msg(c, pixels
                        ? "Modo pixels LIGADO: cada texel vira uma face com pixel proprio no atlas.png (usa a malha unica model_print.obj). "
                            + "Texels totalmente transparentes nao viram face. Use em areas pequenas."
                        : "Modo pixels desligado.");
                })))
            .then(ClientCommandManager.literal("selection")
                .executes(c -> selection(c, true))
                .then(ClientCommandManager.argument("cull", BoolArgumentType.bool())
                    .executes(c -> selection(c, BoolArgumentType.getBool(c, "cull")))))
            .then(ClientCommandManager.literal("radius")
                .then(ClientCommandManager.argument("r", IntegerArgumentType.integer(1, 100))
                    .executes(c -> radius(c, true))
                    .then(ClientCommandManager.argument("cull", BoolArgumentType.bool())
                        .executes(c -> radius(c, BoolArgumentType.getBool(c, "cull"))))))
            .then(ClientCommandManager.literal("box")
                .then(i("x1").then(i("y1").then(i("z1").then(i("x2").then(i("y2").then(i("z2")
                    .executes(c -> box(c, true))
                    .then(ClientCommandManager.argument("cull", BoolArgumentType.bool())
                        .executes(c -> box(c, BoolArgumentType.getBool(c, "cull"))))
                ))))))));
    }

    private static net.minecraft.world.entity.player.Player player() {
        return Minecraft.getInstance().player;
    }

    private static int solid(CommandContext<FabricClientCommandSource> c, float px) {
        solidPx = px;
        return msg(c, px > 0
            ? "Solidify LIGADO: planos finos ganham " + px + " px de espessura (" + (px / 16f) + " bloco), so nos texels opacos. Usa a malha unica model_print.obj."
            : "Solidify desligado.");
    }

    private static int selection(CommandContext<FabricClientCommandSource> c, boolean cull) {
        if (pos1 == null || pos2 == null) return fail(c, "Defina /blockexport pos1 e pos2 primeiro.");
        return run(c, pos1, pos2, cull);
    }

    private static int radius(CommandContext<FabricClientCommandSource> c, boolean cull) {
        int r = IntegerArgumentType.getInteger(c, "r");
        BlockPos p = player().blockPosition();
        return run(c, p.offset(-r, -r, -r), p.offset(r, r, r), cull);
    }

    private static int box(CommandContext<FabricClientCommandSource> c, boolean cull) {
        BlockPos a = new BlockPos(IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "y1"), IntegerArgumentType.getInteger(c, "z1"));
        BlockPos b = new BlockPos(IntegerArgumentType.getInteger(c, "x2"), IntegerArgumentType.getInteger(c, "y2"), IntegerArgumentType.getInteger(c, "z2"));
        return run(c, a, b, cull);
    }

    private static int run(CommandContext<FabricClientCommandSource> c, BlockPos a, BlockPos b, boolean cull) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return fail(c, "Sem mundo carregado.");

        long vol = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
        if (vol > MAX_VOLUME) return fail(c, "Regiao grande demais (" + vol + " blocos). Divida em partes.");

        Path dir = mc.gameDirectory.toPath().resolve("blockexport")
            .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

        msg(c, "Exportando " + vol + " blocos... o jogo pode travar um pouco. (Chunks nao carregados saem vazios!)");
        try {
            ModelExporter.Result r = new ModelExporter(dir, cull, ber, print, printMm, printMin, solidPx, pixels).run(level, a, b);
            String p = dir.toAbsolutePath().toString();
            c.getSource().sendFeedback(
                Component.literal("Pronto: " + r.blocks() + " blocos, " + r.quads() + " quads, " + r.materials() + " materiais. ")
                    .append(Component.literal("[abrir pasta]").withStyle(s -> s
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, p)))));
            if (!r.skipped().isEmpty()) {
                msg(c, r.skipped().size() + " itens pulados (sem modelo/BER, sem textura ou erro). Veja blocks.json > skipped.");
            }
            return 1;
        } catch (Throwable t) {
            t.printStackTrace();
            return fail(c, "Falhou: " + t);
        }
    }

    private static int msg(CommandContext<FabricClientCommandSource> c, String s) {
        c.getSource().sendFeedback(Component.literal(s));
        return 1;
    }

    private static int fail(CommandContext<FabricClientCommandSource> c, String s) {
        c.getSource().sendError(Component.literal(s));
        return 0;
    }

    private ExportCommand() {}
}
