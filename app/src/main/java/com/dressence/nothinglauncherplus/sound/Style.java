package com.dressence.nothinglauncherplus.sound;

/**
 * Background music styles, one for each of Nothing's breathing widgets. Each plays that widget's
 * own breathing loop (from Nothing Widgets on the phone) as its bed and moves with its breath,
 * with generated layers in its key (A minor pentatonic, A4 = 440 Hz as Nothing's loops are):
 * PS4-menu layers (open chord swells, ringing bell tones) and PS2-menu layers (air that opens and
 * closes, a deep rumble, a fluttering cluster, glints), weighted per style. Levels are 0..1.
 */
public enum Style {

    /** Calm's two-breath loop under slow PS4-style chord swells and bells. */
    CALM("Calm", "PS4 menu warmth · chord swells and bells on Nothing's Calm breathing",
            "calm_breathing_loop", 8f,
            0.85f, 0.55f, 0.30f, 0.55f, 0.15f, 0f, 0.05f, 0.25f, 1f),

    /** Focus's box breathing under PS2-style air, rumble and a fluttering cluster. */
    FOCUS("Focus", "PS2 menu air · breathing noise, rumble and flutter on Nothing's Focus breath",
            "focus_breathing_loop", 16f,
            0.70f, 0.25f, 0.35f, 0.15f, 0.60f, 0.45f, 0.55f, 0.35f, 2f),

    /** Relax's long breaths under one deep chord per breath, a slow tide and rare chimes. */
    RELAX("Relax", "Both menus, slowest · one deep chord per breath, a slow tide, rare chimes",
            "relax_breathing_loop", 38f,
            0.68f, 0.50f, 0.40f, 0.25f, 0.35f, 0.25f, 0.20f, 0.30f, 2f);

    public final String title;
    public final String summary;
    /** The breathing loop in Nothing Widgets ({@code res/raw}). */
    final String bed;
    /** Its length, for the breathing curve when Nothing Widgets is not there. */
    final float breathSeconds;
    final float bedLevel;
    final float padLevel;
    final float subLevel;
    final float bellLevel;
    final float airLevel;
    final float rumbleLevel;
    final float flutterLevel;
    final float glintLevel;
    /** Chords per loop of the bed: each breath (or pair) a new one. */
    final float chordsPerLoop;

    Style(String title, String summary, String bed, float breathSeconds, float bedLevel,
            float padLevel, float subLevel, float bellLevel, float airLevel, float rumbleLevel,
            float flutterLevel, float glintLevel, float chordsPerLoop) {
        this.title = title;
        this.summary = summary;
        this.bed = bed;
        this.breathSeconds = breathSeconds;
        this.bedLevel = bedLevel;
        this.padLevel = padLevel;
        this.subLevel = subLevel;
        this.bellLevel = bellLevel;
        this.airLevel = airLevel;
        this.rumbleLevel = rumbleLevel;
        this.flutterLevel = flutterLevel;
        this.glintLevel = glintLevel;
        this.chordsPerLoop = chordsPerLoop;
    }

    /** The saved style, or Calm (also for the names of the styles this replaced). */
    public static Style parse(String name) {
        for (Style style : values()) {
            if (style.name().equals(name)) {
                return style;
            }
        }
        return CALM;
    }
}
