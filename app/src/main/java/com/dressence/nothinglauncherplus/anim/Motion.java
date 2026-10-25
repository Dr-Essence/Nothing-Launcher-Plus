package com.dressence.nothinglauncherplus.anim;

import static com.dressence.nothinglauncherplus.anim.Curves.PI;
import static com.dressence.nothinglauncherplus.anim.Curves.TAU;
import static com.dressence.nothinglauncherplus.anim.Curves.cos;
import static com.dressence.nothinglauncherplus.anim.Curves.ease;
import static com.dressence.nothinglauncherplus.anim.Curves.easeIn;
import static com.dressence.nothinglauncherplus.anim.Curves.envelope;
import static com.dressence.nothinglauncherplus.anim.Curves.hump;
import static com.dressence.nothinglauncherplus.anim.Curves.keys;
import static com.dressence.nothinglauncherplus.anim.Curves.noise;
import static com.dressence.nothinglauncherplus.anim.Curves.seg;
import static com.dressence.nothinglauncherplus.anim.Curves.sin;
import static com.dressence.nothinglauncherplus.anim.Curves.spring;
import static com.dressence.nothinglauncherplus.anim.Curves.wobble;
import static com.dressence.nothinglauncherplus.anim.Moves.hop;
import static com.dressence.nothinglauncherplus.anim.Moves.taps;

/**
 * Hand-tuned emoji motions. Each one plays for {@link #duration} seconds, starts and ends at
 * rest, and then pauses for about {@link #rest} seconds before playing again; the pause keeps
 * the drawer calm and lets the display drop its refresh rate between plays.
 *
 * <p>{@link #pose} must not allocate: it runs every frame for every visible cover.
 */
public enum Motion {

    NONE("Static", 0, 0) {
        @Override
        void pose(float t, Pose p) {
        }
    },

    // ----- Faces ------------------------------------------------------------------------------

    BOB("Bob", 1.6f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float h = hump(t / duration);
            p.ty = -0.06f * h;
            p.scaleY = 1 + 0.03f * h;
            p.scaleX = 1 - 0.015f * h;
        }
    },
    HAPPY("Happy hop", 1.1f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.10f, 0.34f);
        }
    },
    GIGGLE("Giggle", 1.4f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.15f, 0.4f);
            p.rotation = 6 * sin(TAU * 5.5f * t) * e;
            p.ty = -0.03f * Math.abs(sin(TAU * 2.75f * t)) * e;
        }
    },
    LAUGH("Laugh", 1.6f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.15f, 0.4f);
            p.rotation = 10 * sin(TAU * 6 * t) * e;
            p.ty = -0.05f * Math.abs(sin(TAU * 3 * t)) * e;
            p.scaleX = p.scaleY = 1 + 0.05f * e;
        }
    },
    LOVE("Love", 1.8f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float beat = hump(t / 0.3f) + 0.7f * hump((t - 0.34f) / 0.3f)
                    + hump((t - 0.9f) / 0.3f) + 0.7f * hump((t - 1.24f) / 0.3f);
            p.scaleX = p.scaleY = 1 + 0.12f * beat;
            p.rotation = 5 * sin(TAU * t / duration) * sin(PI * t / duration);
        }
    },
    HEARTBEAT("Heartbeat", 0.9f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            // "Lub-dub": a strong beat and a softer echo.
            float beat = hump(t / 0.24f) + 0.6f * hump((t - 0.28f) / 0.24f);
            p.scaleX = p.scaleY = 1 + 0.16f * beat;
        }
    },
    KISS("Kiss", 1.3f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = keys(t, K.KISS_SCALE);
            p.rotation = keys(t, K.KISS_ROT);
        }
    },
    TILT("Ponder", 2.4f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            p.rotation = -12 * (spring(t, 1.3f, 0.55f) - spring(t - 1.3f, 1.3f, 0.6f));
            p.pivotY = 0.8f;
        }
    },
    ROLL("Eye roll", 2.0f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            p.rotation = -12 * sin(TAU * u) * sin(PI * u);
            p.ty = -0.02f * hump(u);
        }
    },
    JELLY("Jelly", 1.5f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float w = wobble(t, 3.0f, 0.16f);
            p.pivotY = 1;
            p.scaleX = 1 + 0.10f * w;
            p.scaleY = 1 - 0.10f * w;
        }
    },
    PEEK("Peek", 2.0f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.tx = keys(t, K.PEEK);
            p.rotation = p.tx * 70;
        }
    },
    SHIVER("Shiver", 1.1f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.1f, 0.3f);
            p.tx = 0.02f * sin(TAU * 15 * t) * e;
            p.rotation = 2.5f * sin(TAU * 11 * t + 1) * e;
        }
    },
    RAGE("Rage", 1.4f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.15f, 0.35f);
            p.pivotY = 0.9f;
            p.scaleX = p.scaleY = 1 + 0.08f * e;
            p.rotation = 7 * sin(TAU * 9 * t) * e;
            p.ty = 0.01f * sin(TAU * 18 * t) * e;
        }
    },
    SURPRISE("Gasp", 1.2f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.08f, 0.28f);
            float pop = 1 + 0.06f * hump(t / 0.6f);
            p.scaleX *= pop;
            p.scaleY *= pop;
        }
    },
    SCREAM("Scream", 1.6f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.09f, 0.28f);
            if (t > 0.5f) {
                p.tx = 0.02f * sin(TAU * 16 * t) * envelope(t - 0.5f, 1.1f, 0.1f, 0.3f);
            }
        }
    },
    SOB("Sob", 1.6f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.2f, 0.4f);
            p.pivotY = 1;
            p.ty = (0.02f * sin(TAU * 7 * t) + 0.02f) * e;
            p.scaleY = 1 - 0.04f * Math.abs(sin(TAU * 3.5f * t)) * e;
        }
    },
    DROOP("Sigh", 2.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float d = keys(t, K.DROOP);
            p.pivotY = 1;
            p.ty = 0.05f * d;
            p.rotation = -6 * d;
            p.scaleY = 1 - 0.04f * d;
        }
    },
    SLEEP("Doze", 3.0f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float h = hump(t / duration);
            p.scaleX = p.scaleY = 1 + 0.05f * h;
            p.ty = -0.02f * h;
            p.rotation = -4 * h;
        }
    },
    BREATHE("Breathe", 3.0f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 + 0.045f * hump(t / duration);
        }
    },
    DIZZY("Dizzy", 2.4f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.3f, 0.5f);
            float a = TAU * 1.25f * t;
            p.rotation = 12 * sin(a) * e;
            p.tx = 0.04f * cos(a) * e;
            p.ty = 0.03f * sin(a) * e;
        }
    },
    EXPLODE("Mind blown", 1.7f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            if (t < 0.55f) {
                p.tx = 0.015f * sin(TAU * 18 * t) * envelope(t, 0.55f, 0.1f, 0.05f);
            }
            float w = wobble(t - 0.55f, 2.2f, 0.3f);
            p.scaleX = p.scaleY = 1 + 0.24f * w;
            p.rotation = 6 * wobble(t - 0.55f, 3.5f, 0.3f);
        }
    },
    PARTY("Party", 1.5f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.08f, 0.32f);
            // Turn around the centre; the hop's bottom pivot would swing it in an arc.
            p.pivotY = 0.5f;
            p.spin = 360 * ease(seg(t, 0.12f, 0.9f));
        }
    },
    COOL("Cool", 2.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float c = keys(t, K.HOLD);
            p.tx = 0.035f * c;
            p.rotation = -5 * c;
        }
    },
    WOOZY("Woozy", 2.4f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.3f, 0.5f);
            p.rotation = 9 * sin(TAU * t / 1.2f) * e;
            p.ty = 0.015f * sin(TAU * t / 0.6f) * e;
        }
    },
    SNEEZE("Sneeze", 1.7f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float lean = ease(seg(t, 0, 0.5f));
            float release = spring(t - 0.5f, 3, 0.6f);
            float jerk = wobble(t - 0.5f, 2.5f, 0.35f);
            p.pivotY = 0.9f;
            p.rotation = 8 * lean * (1 - release) - 14 * jerk;
            p.tx = -0.04f * jerk;
            p.scaleY = 1 + 0.05f * lean * (1 - release);
        }
    },
    MELT("Melt", 2.6f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float m = keys(t, K.MELT);
            p.pivotY = 1;
            p.scaleY = 1 - 0.14f * m;
            p.scaleX = 1 + 0.10f * m;
        }
    },
    FLIP("Flip", 2.6f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.spin = 180 * spring(t, 1.1f, 0.55f) + 180 * spring(t - 1.1f, 1.1f, 0.55f);
        }
    },
    FLOAT("Float", 3.0f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            p.ty = -0.06f * hump(u);
            p.rotation = 4 * sin(TAU * u) * sin(PI * u);
        }
    },
    GHOST("Haunt", 3.0f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            p.ty = -0.06f * hump(u);
            p.tx = 0.03f * sin(TAU * u) * sin(PI * u);
            p.rotation = 5 * sin(TAU * u) * sin(PI * u);
            p.alpha = 1 - 0.3f * hump(u);
        }
    },
    ROBOT("Robot", 1.6f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.rotation = keys(t, K.ROBOT);
        }
    },

    // ----- Hands and people -------------------------------------------------------------------

    WAVE("Wave", 1.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.pivotX = 0.75f;
            p.pivotY = 0.95f;
            p.rotation = keys(t, K.WAVE);
        }
    },
    HIGH_FIVE("High five", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float w = wobble(t, 1.8f, 0.4f);
            p.scaleX = p.scaleY = 1 + 0.16f * w;
            p.rotation = -6 * w;
        }
    },
    PINCH("Pinch", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 - 0.07f * taps(t, 2, 0.35f, 0.3f);
        }
    },
    ROCK_ON("Rock on", 1.1f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.pivotY = 0.9f;
            p.rotation = 10 * sin(TAU * 4.5f * t) * envelope(t, duration, 0.1f, 0.3f);
        }
    },
    POINT_UP("Point up", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.ty = -0.07f * taps(t, 2, 0.45f, 0.4f);
        }
    },
    POINT_DOWN("Point down", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.ty = 0.07f * taps(t, 2, 0.45f, 0.4f);
        }
    },
    POINT_LEFT("Point left", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.tx = -0.07f * taps(t, 2, 0.45f, 0.4f);
        }
    },
    POINT_RIGHT("Point right", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.tx = 0.07f * taps(t, 2, 0.45f, 0.4f);
        }
    },
    POINT_AT("Point at you", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 + 0.12f * taps(t, 2, 0.45f, 0.4f);
        }
    },
    THUMB_UP("Thumbs up", 1.1f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float w = wobble(t, 1.7f, 0.4f);
            p.ty = -0.06f * w;
            p.rotation = -10 * w;
            p.scaleX = p.scaleY = 1 + 0.08f * w;
        }
    },
    THUMB_DOWN("Thumbs down", 1.1f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float w = wobble(t, 1.7f, 0.4f);
            p.ty = 0.06f * w;
            p.rotation = 10 * w;
            p.scaleX = p.scaleY = 1 + 0.04f * w;
        }
    },
    PUNCH("Punch", 1.1f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float s;
            if (t < 0.18f) {
                s = 1 - 0.08f * ease(t / 0.18f);
            } else {
                float x = t - 0.18f;
                s = 1 - 0.08f * (1 - spring(x, 5, 0.5f)) + 0.22f * wobble(x, 2.2f, 0.35f);
            }
            p.scaleX = p.scaleY = s;
        }
    },
    CLAP("Clap", 1.0f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float c = taps(t, 3, 0.3f, 0.22f);
            p.scaleX = 1 - 0.12f * c;
            p.scaleY = 1 + 0.04f * c;
        }
    },
    HOORAY("Hooray", 1.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.12f, 0.36f);
        }
    },
    PRAY("Pray", 1.8f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float h = keys(t, K.PRESS);
            p.scaleX = p.scaleY = 1 - 0.06f * h;
            p.ty = 0.015f * h;
        }
    },
    WRITE("Write", 1.6f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.2f, 0.3f);
            p.tx = 0.014f * sin(TAU * 6 * t) * e;
            p.ty = 0.01f * sin(TAU * 12 * t) * e;
            p.rotation = 3 * sin(TAU * 6 * t + 1) * e;
        }
    },
    FLEX("Flex", 1.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float s = taps(t, 2, 0.5f, 0.4f);
            p.scaleX = p.scaleY = 1 + 0.10f * s;
            p.rotation = -6 * s;
        }
    },
    NOD("Nod", 1.3f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float n = taps(t, 2, 0.45f, 0.4f);
            p.ty = 0.03f * n;
            p.rotation = 3 * n;
        }
    },
    DANCE("Dance", 2.0f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.2f, 0.4f);
            p.rotation = 10 * sin(TAU * 2 * t) * e;
            p.ty = -0.04f * Math.abs(sin(TAU * 2 * t)) * e;
            p.tx = 0.025f * sin(TAU * t) * e;
        }
    },
    RUN("Run", 1.4f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.2f, 0.3f);
            p.ty = -0.035f * Math.abs(sin(TAU * 3.5f * t)) * e;
            p.rotation = -6 * e;
            p.tx = -0.02f * sin(TAU * 1.75f * t) * e;
        }
    },

    // ----- Animals and nature -----------------------------------------------------------------

    FLY("Fly", 3.0f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            p.tx = 0.06f * sin(TAU * u) * s;
            p.ty = 0.035f * sin(2 * TAU * u) * s;
            p.rotation = 8 * cos(TAU * u) * s;
        }
    },
    FLUTTER("Flutter", 1.4f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.15f, 0.3f);
            p.scaleX = 1 - 0.18f * Math.abs(sin(TAU * 4 * t)) * e;
            p.ty = -0.04f * hump(t / duration);
        }
    },
    FLAP("Flap", 1.4f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.15f, 0.3f);
            p.scaleY = 1 - 0.10f * Math.abs(sin(TAU * 4.5f * t)) * e;
            p.ty = -0.05f * hump(t / duration);
            p.rotation = 4 * sin(TAU * 2.25f * t) * e;
        }
    },
    SWIM("Swim", 2.6f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            p.tx = 0.05f * sin(TAU * u) * s;
            p.ty = 0.015f * sin(2 * TAU * u) * s;
            p.rotation = -7 * cos(TAU * u) * s;
        }
    },
    HOP("Hop", 1.5f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.09f, 0.3f);
            hop(p, t - 0.75f, 0.05f, 0.22f);
        }
    },
    CRAWL("Crawl", 2.0f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            float c = sin(2 * TAU * u) * s;
            p.scaleX = 1 + 0.08f * c;
            p.scaleY = 1 - 0.05f * c;
            p.tx = 0.02f * sin(TAU * u) * s;
        }
    },
    SLITHER("Slither", 2.0f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            p.rotation = 7 * sin(3 * PI * u) * s;
            p.tx = 0.025f * sin(3 * PI * u + 1) * s;
        }
    },
    SWAY("Sway", 3.0f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            p.pivotY = 1;
            p.rotation = 6 * sin(TAU * u) * sin(PI * u);
        }
    },
    DRIFT("Drift", 2.6f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            p.tx = 0.04f * sin(TAU * u) * s;
            p.ty = 0.03f * hump(u);
            p.rotation = 12 * sin(TAU * u) * s;
        }
    },

    // ----- Food, sky and places ---------------------------------------------------------------

    SIP("Sip", 1.8f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float k = keys(t, K.HOLD_LONG);
            p.rotation = -18 * k;
            p.ty = -0.02f * k;
        }
    },
    TURN("Turn", 2.0f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.spin = 360 * ease(seg(t, 0, 1.8f));
        }
    },
    SPIN("Spin", 1.5f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.spin = 360 * spring(t, 0.9f, 0.7f);
        }
    },
    TWINKLE("Twinkle", 1.2f, 1.2f) {
        @Override
        void pose(float t, Pose p) {
            float w = wobble(t, 1.6f, 0.4f);
            p.scaleX = p.scaleY = 1 + 0.16f * w;
            p.rotation = 14 * w;
            p.alpha = 1 - 0.2f * hump(t / 0.5f);
        }
    },
    FLICKER("Flicker", 2.4f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.3f, 0.4f);
            float n = noise(t, 1);
            p.pivotY = 1;
            p.scaleY = 1 + 0.07f * n * e;
            p.scaleX = 1 - 0.035f * n * e;
            p.rotation = 2.5f * noise(t, 4) * e;
        }
    },
    FLASH("Flash", 0.9f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.alpha = keys(t, K.FLASH);
            p.scaleX = p.scaleY = 1 + 0.08f * hump(t / 0.5f);
        }
    },
    ROCK("Rock", 2.8f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            p.rotation = 7 * sin(TAU * u) * s;
            p.ty = 0.02f * sin(2 * TAU * u) * s;
        }
    },
    DRIP("Drip", 1.3f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.pivotY = 0;
            p.scaleY = 1 + 0.12f * hump(t / 0.5f);
            p.ty = 0.04f * hump((t - 0.4f) / 0.7f);
        }
    },
    WHOOSH("Whoosh", 1.3f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.tx = t < 0.35f ? -0.09f * ease(t / 0.35f)
                    : -0.09f * (1 - spring(t - 0.35f, 2.5f, 0.45f));
            p.alpha = 1 - 0.5f * hump(t / 0.7f);
        }
    },
    SETTLE("Settle", 1.2f, 2.0f) {
        @Override
        void pose(float t, Pose p) {
            float w = wobble(t, 2.2f, 0.35f);
            p.pivotY = 1;
            p.scaleY = 1 + 0.05f * w;
            p.scaleX = 1 - 0.03f * w;
        }
    },
    GLOBE("Globe", 3.0f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            p.rotationY = 26 * sin(TAU * u) * sin(PI * u);
        }
    },

    // ----- Transport --------------------------------------------------------------------------

    DRIVE("Drive", 1.6f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.tx = keys(t, K.DRIVE);
            p.ty = 0.008f * sin(TAU * 9 * t) * envelope(t, duration, 0.2f, 0.3f);
            p.rotation = keys(t, K.DRIVE_TILT);
        }
    },
    LAUNCH("Launch", 2.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            if (t < 0.5f) {
                p.tx = 0.012f * sin(TAU * 20 * t) * envelope(t, 0.5f, 0.1f, 0.05f);
            } else if (t < 1.1f) {
                float l = easeIn(seg(t, 0.5f, 1.1f));
                p.tx = 0.16f * l;
                p.ty = -0.16f * l;
                // Fully faded just before the jump back to the start position.
                p.alpha = 1 - ease(seg(t, 0.6f, 1.05f));
            } else {
                // Re-enter from the lower left while still transparent, then settle.
                float r = 1 - spring(t - 1.1f, 1.6f, 0.55f);
                p.tx = -0.12f * r;
                p.ty = 0.12f * r;
                p.alpha = seg(t, 1.12f, 1.37f);
            }
        }
    },

    // ----- Objects ----------------------------------------------------------------------------

    BUZZ("Buzz", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float e = bursts(t);
            p.tx = 0.025f * sin(TAU * 24 * t) * e;
            p.rotation = 4 * sin(TAU * 24 * t + 1.3f) * e;
        }
    },
    TYPE("Type", 1.4f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.15f, 0.25f);
            float k = Math.abs(sin(TAU * 7 * t)) * e;
            p.pivotY = 1;
            p.ty = 0.012f * k;
            p.scaleY = 1 - 0.02f * k;
        }
    },
    RING("Ring", 2.0f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.pivotY = 0.08f;
            p.rotation = 20 * wobble(t, 2.2f, 0.18f);
        }
    },
    ALARM("Alarm", 1.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float e = bursts(t);
            p.rotation = 10 * sin(TAU * 16 * t) * e;
            p.ty = -0.01f * e;
        }
    },
    TICK("Tick", 1.6f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float a = hump(t / 0.2f);
            float b = hump((t - 0.5f) / 0.2f);
            float c = hump((t - 1.0f) / 0.2f);
            p.scaleX = p.scaleY = 1 + 0.05f * (a + b + c);
            p.rotation = 3 * (a - b + c);
        }
    },
    COIN("Coin flip", 1.4f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.08f, 0.3f);
            p.spinY = 360 * ease(seg(t, 0.1f, 1.0f));
        }
    },
    UNLOCK("Unlock", 1.8f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.rotation = -24 * (spring(t, 1.5f, 0.55f) - spring(t - 0.9f, 1.5f, 0.6f));
        }
    },
    HAMMER("Hammer", 1.3f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.pivotX = 0.25f;
            p.pivotY = 0.85f;
            if (t < 0.42f) {
                p.rotation = keys(t, K.HAMMER);
            } else {
                p.rotation = 10 * (1 - spring(t - 0.42f, 3, 0.5f));
            }
            p.scaleY = 1 - 0.06f * hump((t - 0.36f) / 0.2f);
        }
    },
    SNIP("Snip", 1.0f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.rotation = 8 * sin(TAU * 6 * t) * envelope(t, duration, 0.1f, 0.25f);
        }
    },
    SEARCH("Search", 1.8f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float a = TAU * ease(t / duration);
            p.tx = 0.045f * (cos(a) - 1);
            p.ty = 0.045f * sin(a);
        }
    },
    POP("Pop", 1.0f, 1.8f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 + 0.2f * wobble(t, 2.0f, 0.38f);
        }
    },
    STAMP("Stamp", 1.1f, 1.8f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 + 0.3f * hump(t / 0.26f)
                    - 0.08f * wobble(t - 0.2f, 3, 0.35f);
            p.rotation = -10 * hump(t / 0.32f);
        }
    },
    NO("No", 1.2f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.tx = 0.05f * wobble(t, 4.5f, 0.2f);
        }
    },
    ALERT("Alert", 1.1f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 + 0.12f * taps(t, 2, 0.45f, 0.35f);
        }
    },
    WONDER("Wonder", 1.6f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            p.pivotY = 1;
            p.rotation = 12 * wobble(t, 2.2f, 0.25f);
        }
    },
    PULSE("Pulse", 1.4f, 1.8f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleX = p.scaleY = 1 + 0.08f * hump(t / duration);
        }
    },
    RATTLE("Rattle", 1.6f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            if (t < 0.6f) {
                p.rotation = 6 * sin(TAU * 11 * t) * envelope(t, 0.6f, 0.05f, 0.1f);
            }
            p.scaleX = p.scaleY = 1 + 0.15f * wobble(t - 0.65f, 2, 0.4f);
        }
    },
    GROOVE("Groove", 2.0f, 1.0f) {
        @Override
        void pose(float t, Pose p) {
            float e = envelope(t, duration, 0.2f, 0.4f);
            p.rotation = 8 * sin(TAU * 2 * t) * e;
            p.ty = -0.03f * Math.abs(sin(TAU * 2 * t)) * e;
        }
    },
    DRUM("Drum", 1.2f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            float c = taps(t, 4, 0.2f, 0.14f);
            p.pivotY = 1;
            p.scaleY = 1 - 0.08f * c;
            p.scaleX = 1 + 0.05f * c;
        }
    },
    BOUNCE("Bounce", 1.9f, 1.4f) {
        @Override
        void pose(float t, Pose p) {
            hop(p, t, 0.14f, 0.40f);
            hop(p, t - 1.0f, 0.06f, 0.24f);
        }
    },
    GROW("Grow", 1.4f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float g = keys(t, K.GROW);
            p.pivotY = 1;
            p.scaleY = 1 + 0.16f * g;
            p.scaleX = 1 + 0.04f * g;
        }
    },
    SHRINK("Shrink", 1.4f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            float g = keys(t, K.GROW);
            p.pivotY = 1;
            p.scaleY = 1 - 0.14f * g;
            p.scaleX = 1 + 0.03f * g;
        }
    },
    SEND("Send", 1.7f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            if (t < 0.25f) {
                p.tx = -0.02f * ease(t / 0.25f);
            } else if (t < 0.6f) {
                float l = easeIn(seg(t, 0.25f, 0.6f));
                p.tx = -0.02f + 0.14f * l;
                p.rotation = -6 * l;
                p.alpha = 1 - ease(seg(t, 0.3f, 0.56f));
            } else {
                float r = 1 - spring(t - 0.6f, 1.6f, 0.55f);
                p.tx = -0.10f * r;
                p.alpha = seg(t, 0.62f, 0.82f);
            }
        }
    },
    FLAG("Wave flag", 2.2f, 0.8f) {
        @Override
        void pose(float t, Pose p) {
            float u = t / duration;
            float s = sin(PI * u);
            p.pivotX = 0.1f;
            p.rotationY = 24 * sin(3 * PI * u) * s;
            p.rotation = 2 * sin(3 * PI * u + 1) * s;
        }
    },
    BLINK("Blink", 1.0f, 2.0f) {
        @Override
        void pose(float t, Pose p) {
            p.scaleY = 1 - 0.15f * taps(t, 2, 0.3f, 0.16f);
        }
    },
    PAGE("Page flip", 1.6f, 1.6f) {
        @Override
        void pose(float t, Pose p) {
            p.rotationY = -35 * (spring(t, 1.4f, 0.6f) - spring(t - 0.8f, 1.4f, 0.6f));
        }
    };

    /** Human-readable name shown in the settings. */
    public final String title;
    /** Seconds of movement per play. */
    public final float duration;
    /** Typical pause between plays, in seconds. */
    public final float rest;

    Motion(String title, float duration, float rest) {
        this.title = title;
        this.duration = duration;
        this.rest = rest;
    }

    /** Fills {@code p} (already reset) for {@code t} seconds into the play. */
    abstract void pose(float t, Pose p);

    /** Two short bursts, as for a buzzing phone or ringing alarm. */
    static float bursts(float t) {
        return t < 0.4f ? envelope(t, 0.35f, 0.04f, 0.08f)
                : envelope(t - 0.5f, 0.35f, 0.04f, 0.08f);
    }

    public static Motion parse(String name) {
        if (name == null || name.isEmpty()) {
            return NONE;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /** Keyframe tables ({@code t, value} pairs), kept static so poses never allocate. */
    private static final class K {
        static final float[] KISS_SCALE = {0, 1, 0.2f, 0.92f, 0.4f, 1.14f, 0.75f, 0.98f, 1.1f, 1};
        static final float[] KISS_ROT = {0, 0, 0.2f, 4, 0.42f, -10, 0.8f, 2, 1.2f, 0};
        static final float[] PEEK = {0, 0, 0.35f, -0.07f, 0.8f, -0.07f, 1.15f, 0.07f, 1.55f, 0.07f,
                2.0f, 0};
        static final float[] DROOP = {0, 0, 0.5f, 1, 1.5f, 1, 2.2f, 0};
        static final float[] HOLD = {0, 0, 0.5f, 1, 1.5f, 1, 2.2f, 0};
        static final float[] HOLD_LONG = {0, 0, 0.5f, 1, 1.1f, 1, 1.8f, 0};
        static final float[] MELT = {0, 0, 1.0f, 1, 1.6f, 1, 2.6f, 0};
        static final float[] PRESS = {0, 0, 0.5f, 1, 1.2f, 1, 1.8f, 0};
        static final float[] ROBOT = {0, 0, 0.12f, -10, 0.45f, -10, 0.57f, 10, 0.9f, 10, 1.02f, 0};
        static final float[] WAVE = {0, 0, 0.18f, -18, 0.4f, 14, 0.62f, -16, 0.84f, 12, 1.1f, 0};
        static final float[] FLASH = {0, 1, 0.08f, 0.35f, 0.18f, 1, 0.28f, 0.5f, 0.4f, 1};
        static final float[] DRIVE = {0, 0, 0.25f, 0.03f, 0.6f, -0.07f, 1.0f, -0.07f, 1.6f, 0};
        static final float[] DRIVE_TILT = {0, 0, 0.25f, 2, 0.6f, -3, 1.0f, 0};
        static final float[] HAMMER = {0, 0, 0.3f, -24, 0.42f, 10};
        static final float[] GROW = {0, 0, 0.45f, 1, 0.8f, 1, 1.4f, 0};
    }
}
