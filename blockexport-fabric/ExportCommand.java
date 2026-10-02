package com.example.blockexport;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
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
 * /blockexport thickness <0-8>                   -> modo impressao: espessura minima em pixels (1/16 de bloco). Padrao 2; 0 desliga.
 *                                                   Folhas sem espessura (plantas, trilhos, vinhas) viram placas e paredes finas
 *                                                   (tapete, placas de 1 px, discos) sao engrossadas ate esse minimo. Vale pra
 *                                                   qualquer bloco, de qualquer mod, inclusive BlockEntityRenderers.
 * /blockexport ams <true|false> [maxCores]       -> cores de filamento Bambu Lab (AMS) no lugar da textura; liga o modo impressao.
 *                                                   maxCores = filamentos diferentes no maximo (padrao 4 = um AMS; 0 = sem limite)
 * /blockexport amsdetail <1-16>                  -> celulas por lado de cada face (1 = uma cor por face; 16 = pixel a pixel)
 *
 * Paleta propria: config/blockexport/ams_palette.txt, uma cor por linha ("Nome #RRGGBB").
 *
 * cull = true (padrao) remove faces encobertas; false mantem todas as faces.
 */
public final class ExportCommand {
    private static final long MAX_VOLUME = 20_000_000L;
    private static BlockPos pos1, pos2;
    private static boolean ber = true;
    private static boolean print = false;
    private static int thickness = 2;
    private static boolean ams = false;
    private static int amsColors = 4;
    private static int amsDetail = 1;

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
                        ? "Modo impressao 3D LIGADO: sai model_print.obj + atlas.png (1 objeto, 1 textura, sem faces internas)."
                        : "Modo impressao 3D desligado (1 objeto por bloco, 1 textura por sprite).");
                })))
            .then(ClientCommandManager.literal("thickness")
                .then(ClientCommandManager.argument("px", IntegerArgumentType.integer(0, 8)).executes(c -> {
                    thickness = IntegerArgumentType.getInteger(c, "px");
                    return msg(c, thickness == 0
                        ? "Espessura minima desligada."
                        : "Espessura minima = " + thickness + " px (" + thickness + "/16 de bloco). Vale no modo impressao 3D e no modo AMS.");
                })))
            .then(ClientCommandManager.literal("ams")
                .then(ClientCommandManager.argument("on", BoolArgumentType.bool())
                    .executes(c -> setAms(c, BoolArgumentType.getBool(c, "on"), amsColors))
                    .then(ClientCommandManager.argument("maxColors", IntegerArgumentType.integer(0, 16))
                        .executes(c -> setAms(c, BoolArgumentType.getBool(c, "on"), IntegerArgumentType.getInteger(c, "maxColors"))))))
            .then(ClientCommandManager.literal("amsdetail")
                .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 16)).executes(c -> {
                    amsDetail = IntegerArgumentType.getInteger(c, "n");
                    return msg(c, "Detalhe AMS = " + amsDetail + "x" + amsDetail + " celulas por face"
                        + (amsDetail == 1 ? " (uma cor por face)." : "."));
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

    private static int setAms(CommandContext<FabricClientCommandSource> c, boolean on, int maxColors) {
        ams = on;
        amsColors = maxColors;
        if (!on) return msg(c, "Modo AMS desligado.");
        return msg(c, "Modo AMS LIGADO (" + (maxColors == 0 ? "sem limite de cores" : "ate " + maxColors + " filamentos")
            + ", detalhe " + amsDetail + "x" + amsDetail + "): sai model_ams.obj + model_ams.mtl com 1 material por filamento. "
            + "Implica o modo impressao 3D.");
    }

    private static net.minecraft.world.entity.player.Player player() {
        return Minecraft.getInstance().player;
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
            Path palette = mc.gameDirectory.toPath().resolve("config").resolve("blockexport").resolve("ams_palette.txt");
            ModelExporter.Result r = new ModelExporter(dir,
                new ModelExporter.Options(cull, ber, print, thickness, ams, amsColors, amsDetail, palette)).run(level, a, b);
            String p = dir.toAbsolutePath().toString();
            c.getSource().sendFeedback(
                Component.literal("Pronto: " + r.blocks() + " blocos, " + r.quads() + " quads, " + r.materials() + " materiais. ")
                    .append(Component.literal("[abrir pasta]").withStyle(s -> s
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, p)))));
            for (String note : r.notes()) msg(c, note);
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
