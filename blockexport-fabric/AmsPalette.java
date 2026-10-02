package com.example.blockexport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paleta de filamentos (Bambu Lab) e escolha da cor mais proxima, em CIELAB.
 *
 * Sem classes do Minecraft, pra poder ser testada fora do jogo.
 *
 * A paleta padrao e a de PLA Basic da Bambu Lab (codigos hex da pagina oficial da loja). Pra usar os filamentos
 * que voce realmente tem (outros materiais, cores novas), crie o arquivo  config/blockexport/ams_palette.txt
 * com uma cor por linha:   Nome da cor #RRGGBB   (linhas com # no comeco sao comentario). Se o arquivo
 * existir e tiver cores validas, ele substitui a paleta padrao.
 */
final class AmsPalette {

    record Filament(String name, int rgb) {}

    static final List<Filament> BAMBU_PLA_BASIC = List.of(
        new Filament("Jade White", 0xFFFFFF),
        new Filament("Beige", 0xF7E6DE),
        new Filament("Gold", 0xE4BD68),
        new Filament("Silver", 0xA6A9AA),
        new Filament("Gray", 0x8E9089),
        new Filament("Bronze", 0x847D48),
        new Filament("Brown", 0x9D432C),
        new Filament("Red", 0xC12E1F),
        new Filament("Magenta", 0xEC008C),
        new Filament("Pink", 0xF55A74),
        new Filament("Orange", 0xFF6A13),
        new Filament("Yellow", 0xF4EE2A),
        new Filament("Bambu Green", 0x00AE42),
        new Filament("Mistletoe Green", 0x3F8E43),
        new Filament("Cyan", 0x0086D6),
        new Filament("Blue", 0x0A2989),
        new Filament("Purple", 0x5E43B7),
        new Filament("Blue Gray", 0x5B6579),
        new Filament("Light Gray", 0xD1D3D5),
        new Filament("Dark Gray", 0x545454),
        new Filament("Black", 0x000000));

    private static final Pattern LINE = Pattern.compile("^(.*?)\\s*#([0-9A-Fa-f]{6})\\s*$");

    private AmsPalette() {}

    /** Le a paleta personalizada, se existir; senao devolve a padrao. */
    static List<Filament> load(Path file) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                List<Filament> custom = new ArrayList<>();
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.isEmpty() || s.startsWith("#") && s.length() != 7) continue;
                    Matcher m = LINE.matcher(s);
                    if (!m.matches()) continue;
                    String name = m.group(1).isBlank() ? "#" + m.group(2).toUpperCase(Locale.ROOT) : m.group(1).trim();
                    custom.add(new Filament(name, Integer.parseInt(m.group(2), 16)));
                }
                if (!custom.isEmpty()) return custom;
            } catch (IOException ignored) {}
        }
        return BAMBU_PLA_BASIC;
    }

    // ------------------------------------------------------------------ cor

    /** sRGB (0xRRGGBB) -> CIELAB (D65). */
    static double[] lab(int rgb) {
        double r = lin(((rgb >> 16) & 255) / 255.0), g = lin(((rgb >> 8) & 255) / 255.0), b = lin((rgb & 255) / 255.0);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }

    private static double f(double t) { return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16) / 116.0; }

    static double dist2(double[] a, double[] b) {
        double dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return dl * dl + da * da + db * db;
    }

    /** Indice da cor da lista (pelo Lab) mais proxima de c. */
    static int nearest(double[] c, List<double[]> pal) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < pal.size(); i++) {
            double d = dist2(c, pal.get(i));
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /**
     * Escolhe ate k filamentos da paleta que melhor representam as cores de origem (cada uma com um peso,
     * normalmente a area): selecao gulosa, a cada passo entra o filamento que mais reduz o erro total.
     * k <= 0 ou k >= tamanho da paleta usa a paleta inteira. Retorna os indices na paleta.
     */
    static List<Integer> choose(List<double[]> src, double[] weight, List<double[]> pal, int k) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) all.add(i);
        if (k <= 0 || k >= pal.size() || src.isEmpty()) return all;

        double[] cur = new double[src.size()];
        java.util.Arrays.fill(cur, Double.MAX_VALUE);
        List<Integer> chosen = new ArrayList<>();
        boolean[] used = new boolean[pal.size()];
        for (int step = 0; step < k; step++) {
            int best = -1;
            double bestCost = Double.MAX_VALUE;
            for (int p = 0; p < pal.size(); p++) {
                if (used[p]) continue;
                double cost = 0;
                for (int s = 0; s < src.size(); s++) {
                    double d = dist2(src.get(s), pal.get(p));
                    cost += weight[s] * Math.min(cur[s], d);
                }
                if (cost < bestCost) { bestCost = cost; best = p; }
            }
            used[best] = true;
            chosen.add(best);
            for (int s = 0; s < src.size(); s++) cur[s] = Math.min(cur[s], dist2(src.get(s), pal.get(best)));
        }
        return chosen;
    }
}
