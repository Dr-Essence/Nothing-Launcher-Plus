package com.dressence.nothinglauncherplus.sound;

/** An interface-sound instrument: how a {@link Voice} is set up before a note starts. */
interface Patch {

    void apply(Voice v);

    /** Navigation tick: a tiny, muted click with a soft room behind it. */
    Patch TICK = v -> {
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

    /** Glass bell (drawer, launching): inharmonic FM, long ring. */
    Patch BELL = v -> {
        v.fmRatio = 3.5f;
        v.fmIndex = 2.2f;
        v.fmDecay = 0.5f;
        v.gain = 0.075f;
        v.attack = 0.002f;
        v.decay = 1.8f;
        v.release = 1.2f;
        v.reverb = 0.7f;
    };

    /** Short chime (folders): gentle FM, quick fade. */
    Patch CHIME = v -> {
        v.fmRatio = 2f;
        v.fmIndex = 0.8f;
        v.fmDecay = 0.1f;
        v.gain = 0.05f;
        v.attack = 0.002f;
        v.decay = 0.35f;
        v.release = 0.2f;
        v.reverb = 0.5f;
    };

    /** Electric piano (menus): FM with a soft bell attack. */
    Patch E_PIANO = v -> {
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
}
