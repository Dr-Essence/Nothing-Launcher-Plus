package com.dressence.nothinglauncherplus.anim;

import java.util.HashMap;
import java.util.Map;

/**
 * A compact 3 x 5 LED font (M, N and W a little wider), so folder names are drawn in crisp whole
 * dots on the same grid as the emoji, small enough to sit under it like a label. Letters are
 * shown in capitals, as on dot-matrix signs; anything without a glyph here (accents, emoji) is
 * left to the caller to raster from the system font.
 */
final class DotFont {

    static final int HEIGHT = 5;

    private static final Map<Character, boolean[][]> GLYPHS = new HashMap<>();

    private DotFont() {
    }

    /** The glyph for {@code c} (letters case-insensitive), or null if there is none. */
    static boolean[][] glyph(char c) {
        return GLYPHS.get(Character.toUpperCase(c));
    }

    private static void def(char c, String... rows) {
        boolean[][] g = new boolean[HEIGHT][rows[0].length()];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                g[y][x] = rows[y].charAt(x) == '#';
            }
        }
        GLYPHS.put(c, g);
    }

    static {
        def('A', ".#.", "#.#", "###", "#.#", "#.#");
        def('B', "##.", "#.#", "##.", "#.#", "##.");
        def('C', ".##", "#..", "#..", "#..", ".##");
        def('D', "##.", "#.#", "#.#", "#.#", "##.");
        def('E', "###", "#..", "##.", "#..", "###");
        def('F', "###", "#..", "##.", "#..", "#..");
        def('G', ".##", "#..", "#.#", "#.#", ".##");
        def('H', "#.#", "#.#", "###", "#.#", "#.#");
        def('I', "###", ".#.", ".#.", ".#.", "###");
        def('J', "..#", "..#", "..#", "#.#", ".#.");
        def('K', "#.#", "#.#", "##.", "#.#", "#.#");
        def('L', "#..", "#..", "#..", "#..", "###");
        def('M', "#...#", "##.##", "#.#.#", "#...#", "#...#");
        def('N', "#..#", "##.#", "#.##", "#..#", "#..#");
        def('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        def('P', "##.", "#.#", "##.", "#..", "#..");
        def('Q', ".#.", "#.#", "#.#", "##.", ".##");
        def('R', "##.", "#.#", "##.", "#.#", "#.#");
        def('S', ".##", "#..", ".#.", "..#", "##.");
        def('T', "###", ".#.", ".#.", ".#.", ".#.");
        def('U', "#.#", "#.#", "#.#", "#.#", "###");
        def('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        def('W', "#...#", "#...#", "#.#.#", "##.##", "#...#");
        def('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        def('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        def('Z', "###", "..#", ".#.", "#..", "###");
        def('0', "###", "#.#", "#.#", "#.#", "###");
        def('1', ".#.", "##.", ".#.", ".#.", "###");
        def('2', "##.", "..#", ".#.", "#..", "###");
        def('3', "##.", "..#", ".#.", "..#", "##.");
        def('4', "#.#", "#.#", "###", "..#", "..#");
        def('5', "###", "#..", "##.", "..#", "##.");
        def('6', ".##", "#..", "###", "#.#", "###");
        def('7', "###", "..#", ".#.", ".#.", ".#.");
        def('8', "###", "#.#", "###", "#.#", "###");
        def('9', "###", "#.#", "###", "..#", "##.");
        def(' ', "..", "..", "..", "..", "..");
        def('.', ".", ".", ".", ".", "#");
        def(',', ".", ".", ".", "#", "#");
        def('!', "#", "#", "#", ".", "#");
        def('?', "##.", "..#", ".#.", "...", ".#.");
        def('-', "...", "...", "###", "...", "...");
        def('_', "...", "...", "...", "...", "###");
        def('&', ".#.", "#.#", ".#.", "#.#", ".##");
        def('+', "...", ".#.", "###", ".#.", "...");
        def('/', "..#", "..#", ".#.", "#..", "#..");
        def(':', ".", "#", ".", "#", ".");
        def('\'', "#", "#", ".", ".", ".");
        def('(', ".#", "#.", "#.", "#.", ".#");
        def(')', "#.", ".#", ".#", ".#", "#.");
        def('#', "#.#", "###", "#.#", "###", "#.#");
        def('@', "###", "#.#", "#.#", "#..", "###");
        def('*', "...", "#.#", ".#.", "#.#", "...");
    }
}
