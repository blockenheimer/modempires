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
 *
 * cull = true (padrao) remove faces encobertas; false mantem todas as faces.
 */
public final class ExportCommand {
    private static final long MAX_VOLUME = 20_000_000L;
    private static BlockPos pos1, pos2;
    private static boolean ber = true;

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
            ModelExporter.Result r = new ModelExporter(dir, cull, ber).run(level, a, b);
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
