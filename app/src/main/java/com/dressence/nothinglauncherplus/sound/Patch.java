package com.dressence.nothinglauncherplus.sound;

/** An instrument: how a {@link Voice} is set up before a note starts. */
interface Patch {

    void apply(Voice v);

    /** NES pulse lead: 25% duty, slow vibrato, softened top end. */
    Patch PULSE_LEAD = v -> {
        v.wave = Voice.PULSE;
        v.duty = 0.25f;
        v.gain = 0.11f;
        v.attack = 0.004f;
        v.decay = 0.35f;
        v.sustain = 0.6f;
        v.release = 0.08f;
        v.vibratoDepth = 0.005f;
        v.vibratoRate = 5.2f;
        v.cutoff = 0.35f;
        v.reverb = 0.25f;
        v.echo = 0.18f;
    };
    /** Quiet 12.5% pulse for arpeggiated harmony. */
    Patch PULSE_ARP = v -> {
        v.wave = Voice.PULSE;
        v.duty = 0.125f;
        v.gain = 0.05f;
        v.attack = 0.002f;
        v.decay = 0.09f;
        v.sustain = 0.2f;
        v.release = 0.05f;
        v.cutoff = 0.3f;
        v.reverb = 0.3f;
    };
    /** NES triangle bass. */
    Patch TRIANGLE_BASS = v -> {
        v.wave = Voice.TRIANGLE;
        v.gain = 0.22f;
        v.attack = 0.003f;
        v.decay = 0.5f;
        v.sustain = 0.7f;
        v.release = 0.05f;
        v.reverb = 0.05f;
    };
    /** Soft kick: a triangle falling in pitch. */
    Patch KICK = v -> {
        v.wave = Voice.TRIANGLE;
        v.gain = 0.22f;
        v.attack = 0.001f;
        v.decay = 0.07f;
        v.drop = 18f;
        v.release = 0.02f;
        v.reverb = 0;
    };
    /** Closed hi-hat: short LFSR noise burst. */
    Patch HAT = v -> {
        v.wave = Voice.NOISE;
        v.gain = 0.035f;
        v.attack = 0.001f;
        v.decay = 0.02f;
        v.release = 0.01f;
        v.cutoff = 0.6f;
        v.reverb = 0.1f;
    };
    /** Brushed snare for the lounge: darker noise with a longer tail. */
    Patch BRUSH = v -> {
        v.wave = Voice.NOISE;
        v.gain = 0.045f;
        v.attack = 0.01f;
        v.decay = 0.12f;
        v.release = 0.05f;
        v.cutoff = 0.25f;
        v.reverb = 0.3f;
    };
    /** Music box: bright FM pluck with a metallic overtone. */
    Patch MUSIC_BOX = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 7f;
        v.fmIndex = 1.2f;
        v.fmDecay = 0.06f;
        v.gain = 0.09f;
        v.attack = 0.001f;
        v.decay = 0.7f;
        v.release = 0.3f;
        v.reverb = 0.45f;
        v.echo = 0.25f;
    };
    /** Glass bell for ambient menus: inharmonic FM, long ring. */
    Patch BELL = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 3.5f;
        v.fmIndex = 2.2f;
        v.fmDecay = 0.5f;
        v.gain = 0.075f;
        v.attack = 0.002f;
        v.decay = 1.8f;
        v.release = 1.2f;
        v.reverb = 0.7f;
        v.echo = 0.3f;
    };
    /** Short sine chime for fast shimmering arpeggios. */
    Patch CHIME = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 2f;
        v.fmIndex = 0.8f;
        v.fmDecay = 0.1f;
        v.gain = 0.05f;
        v.attack = 0.002f;
        v.decay = 0.35f;
        v.release = 0.2f;
        v.reverb = 0.5f;
        v.echo = 0.35f;
    };
    /** Warm detuned-saw pad, slowly fading in and out. */
    Patch PAD = v -> {
        v.wave = Voice.SUPERSAW;
        v.detune = 1.008f;
        v.gain = 0.06f;
        v.attack = 1.6f;
        v.decay = 4f;
        v.sustain = 0.8f;
        v.release = 2.5f;
        v.cutoff = 0.05f;
        v.reverb = 0.6f;
    };
    /** Breathy flute-like lead: sine with vibrato. */
    Patch FLUTE = v -> {
        v.wave = Voice.SINE;
        v.gain = 0.1f;
        v.attack = 0.08f;
        v.decay = 1f;
        v.sustain = 0.75f;
        v.release = 0.4f;
        v.vibratoDepth = 0.006f;
        v.vibratoRate = 4.8f;
        v.reverb = 0.45f;
        v.echo = 0.2f;
    };
    /** Electric piano for lounge chords: FM with a soft bell attack. */
    Patch E_PIANO = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 1f;
        v.fmIndex = 1.6f;
        v.fmDecay = 0.35f;
        v.gain = 0.055f;
        v.attack = 0.003f;
        v.decay = 1.1f;
        v.sustain = 0.25f;
        v.release = 0.25f;
        v.reverb = 0.3f;
    };
    /** Vibraphone lead: bright FM with a long ring. */
    Patch VIBES = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 4f;
        v.fmIndex = 0.7f;
        v.fmDecay = 0.25f;
        v.gain = 0.08f;
        v.attack = 0.002f;
        v.decay = 0.9f;
        v.release = 0.4f;
        v.vibratoDepth = 0.003f;
        v.vibratoRate = 6f;
        v.reverb = 0.35f;
        v.echo = 0.15f;
    };
    /** Round sine bass, plucked. */
    Patch SINE_BASS = v -> {
        v.wave = Voice.SINE;
        v.gain = 0.24f;
        v.attack = 0.006f;
        v.decay = 0.45f;
        v.sustain = 0.45f;
        v.release = 0.08f;
        v.reverb = 0.05f;
    };
    /** Deep sustained drone under ambient pads. */
    Patch DRONE = v -> {
        v.wave = Voice.SINE;
        v.gain = 0.07f;
        v.attack = 2.5f;
        v.decay = 6f;
        v.sustain = 0.9f;
        v.release = 3f;
        v.reverb = 0.2f;
    };
    /**
     * The console-menu tone: one warm, harmonically rich voice (three-ish detuned saws for a
     * gentle chorus), rolled off below 2 kHz, rising softly and ringing on so notes overlap
     * into a chord. Measured against the PS4 theme: same register and spectrum, no bass.
     */
    Patch MENU_TONE = v -> {
        v.wave = Voice.SUPERSAW;
        v.detune = 1.0025f;
        v.spread = 0.75f;
        v.gain = 0.075f;
        v.attack = 0.18f;
        v.decay = 2.5f;
        v.sustain = 0.55f;
        v.release = 2.4f;
        v.cutoff = 0.45f;
        v.reverb = 0.45f;
        v.echo = 0.12f;
    };
    /** The PS2-style cluster: the same tone detuned wide, so it beats and swells slowly. */
    Patch CLUSTER = v -> {
        v.wave = Voice.SUPERSAW;
        v.detune = 1.004f;
        v.spread = 0.9f;
        v.gain = 0.06f;
        v.attack = 1.4f;
        v.decay = 4f;
        v.sustain = 0.6f;
        v.release = 3.5f;
        v.cutoff = 0.34f;
        v.reverb = 0.55f;
    };
    /**
     * The PS2 air: wide, dark noise whose filter opens and closes about every six seconds, the
     * breathing "wind" under the browser. Play two, panned apart, at slightly different clock
     * rates so left and right never match.
     */
    Patch WIND = v -> {
        v.wave = Voice.NOISE;
        v.gain = 0.04f;
        v.attack = 3f;
        v.decay = 10f;
        v.sustain = 1f;
        v.release = 3f;
        v.cutoff = 0.06f;
        v.lowCut = 0.05f;
        v.sweepDepth = 0.9f;
        v.sweepRate = 0.16f;
        v.reverb = 0.35f;
    };
    /** Low rumble under the cluster. */
    Patch RUMBLE = v -> {
        v.wave = Voice.SINE;
        v.gain = 0.018f;
        v.attack = 2f;
        v.decay = 8f;
        v.sustain = 0.8f;
        v.release = 3f;
        v.reverb = 0.1f;
    };
    /** Soft, slow ping for the twinkling line above the cluster. */
    Patch PING = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 2f;
        v.fmIndex = 0.25f;
        v.fmDecay = 0.2f;
        v.gain = 0.03f;
        v.attack = 0.01f;
        v.decay = 0.6f;
        v.release = 0.5f;
        v.reverb = 0.5f;
        v.echo = 0.45f;
    };
    /** Faint sparkle that slides gently down, high above the cluster. */
    Patch GLINT = v -> {
        v.wave = Voice.SINE;
        v.gain = 0.01f;
        v.attack = 0.05f;
        v.decay = 0.9f;
        v.release = 0.5f;
        v.drop = 0.35f;
        v.reverb = 0.6f;
        v.echo = 0.3f;
    };
    /** Lush, wide string pad: supersaw spread across the stereo field, slow bloom. */
    Patch STRINGS = v -> {
        v.wave = Voice.SUPERSAW;
        v.detune = 1.011f;
        v.gain = 0.07f;
        v.attack = 2.4f;
        v.decay = 6f;
        v.sustain = 0.85f;
        v.release = 4.5f;
        v.cutoff = 0.09f;
        v.sweepDepth = 0.45f;
        v.sweepRate = 0.05f;
        v.reverb = 0.65f;
    };
    /** Soft plucked arpeggio note: round FM pluck with a little bite, made for echoes. */
    Patch PLUCK = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 1f;
        v.fmIndex = 1.1f;
        v.fmDecay = 0.12f;
        v.gain = 0.085f;
        v.attack = 0.003f;
        v.decay = 0.55f;
        v.release = 0.4f;
        v.cutoff = 0.3f;
        v.reverb = 0.55f;
        v.echo = 0.4f;
    };
    /** Crystal: a soft, glassy FM tone that rings into the hall. */
    Patch CRYSTAL = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 3.01f;
        v.fmIndex = 1.1f;
        v.fmDecay = 0.9f;
        v.gain = 0.05f;
        v.attack = 0.01f;
        v.decay = 2.2f;
        v.release = 2f;
        v.cutoff = 0.35f;
        v.reverb = 0.85f;
        v.echo = 0.45f;
    };
    /** Deep resonant tone, struck and left to ring in a vast room. */
    Patch BOOM = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 2f;
        v.fmIndex = 0.9f;
        v.fmDecay = 0.6f;
        v.gain = 0.16f;
        v.attack = 0.02f;
        v.decay = 3.5f;
        v.release = 3f;
        v.cutoff = 0.2f;
        v.reverb = 0.8f;
    };
    /** Navigation tick: a tiny, muted click with a soft room behind it. */
    Patch TICK = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 2f;
        v.fmIndex = 0.6f;
        v.fmDecay = 0.01f;
        v.gain = 0.12f;
        v.attack = 0.0008f;
        v.decay = 0.025f;
        v.release = 0.02f;
        v.cutoff = 0.5f;
        v.reverb = 0.6f;
    };
    /** Rising whoosh for the opening swell: noise through a filter that opens and closes. */
    Patch SWELL = v -> {
        v.wave = Voice.NOISE;
        v.gain = 0.09f;
        v.attack = 2.6f;
        v.decay = 1f;
        v.sustain = 0.3f;
        v.release = 2.2f;
        v.cutoff = 0.02f;
        v.sweepDepth = 1.5f;
        v.sweepRate = 0.18f;
        v.reverb = 0.8f;
    };
    /** Warm console-menu pad: very dark, breathing slowly through its filter. */
    Patch WARM_PAD = v -> {
        v.wave = Voice.SUPERSAW;
        v.detune = 1.006f;
        v.gain = 0.075f;
        v.attack = 3f;
        v.decay = 6f;
        v.sustain = 0.85f;
        v.release = 5f;
        v.cutoff = 0.08f;
        v.sweepDepth = 0.5f;
        v.sweepRate = 0.06f;
        v.reverb = 0.7f;
    };
    /** Soft, dark piano-like key: gentle FM, long decay, no bright attack. */
    Patch SOFT_KEYS = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 1f;
        v.fmIndex = 0.55f;
        v.fmDecay = 0.9f;
        v.gain = 0.11f;
        v.attack = 0.015f;
        v.decay = 2.4f;
        v.sustain = 0.08f;
        v.release = 1.8f;
        v.cutoff = 0.22f;
        v.reverb = 0.75f;
        v.echo = 0.25f;
    };
    /** Distant low glass tone for dark menus. */
    Patch LOW_GLASS = v -> {
        v.wave = Voice.FM;
        v.fmRatio = 1.414f;
        v.fmIndex = 1.1f;
        v.fmDecay = 1.5f;
        v.gain = 0.07f;
        v.attack = 0.4f;
        v.decay = 3.5f;
        v.sustain = 0;
        v.release = 3f;
        v.cutoff = 0.15f;
        v.reverb = 0.9f;
        v.echo = 0.35f;
    };
    /** Slow airy whoosh: dark noise swelling through a sweeping filter. */
    Patch AIR = v -> {
        v.wave = Voice.NOISE;
        v.gain = 0.04f;
        v.attack = 4f;
        v.decay = 5f;
        v.sustain = 0.7f;
        v.release = 4f;
        v.cutoff = 0.015f;
        v.sweepDepth = 0.8f;
        v.sweepRate = 0.1f;
        v.reverb = 0.6f;
    };
    /** Ocean swell: very dark noise, slow in and out. */
    Patch SURF = v -> {
        v.wave = Voice.NOISE;
        v.gain = 0.05f;
        v.attack = 2.2f;
        v.decay = 3f;
        v.sustain = 0.6f;
        v.release = 2.5f;
        v.cutoff = 0.02f;
        v.reverb = 0.4f;
    };
}
