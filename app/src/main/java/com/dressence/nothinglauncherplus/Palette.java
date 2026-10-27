package com.dressence.nothinglauncherplus;

/**
 * The colours the module lights things in, wherever it draws them (the drawer's folder
 * screens, the status bar and quick settings, the settings app's previews), so they match.
 */
public final class Palette {

    /** The green of a lit LED panel: the drawer's folder names, the status bar clock. */
    public static final int LED_GREEN = 0xFF4CFF7A;
    /** The glow round lit green: the same green, deeper and soft. */
    public static final int LED_GLOW = 0xAA2BE86A;

    private Palette() {
    }
}
