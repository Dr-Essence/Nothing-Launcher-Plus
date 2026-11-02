package com.dressence.nothinglauncherplus.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays the background music and interface sounds in one process (the launcher, or the module
 * app for previews).
 *
 * <ul>
 *   <li>Music is rendered live by {@link Ambience}, in stereo, on one audio thread, and only
 *       while asked to play: the launcher asks while it is in front and lets go the moment an
 *       app is tapped. The style's breathing loop is read from Nothing Widgets off the audio
 *       path ({@link Beds}). Starts fade in; stops recede over 1.6 s, quieter and more muffled,
 *       through a short buffer so the fade is heard at once; a new style fades the old one out
 *       first.</li>
 *   <li>It never takes audio focus, so it can't pause anyone's music; instead it fades out while
 *       any other app plays media or a call is active, and comes back afterwards.</li>
 *   <li>Interface sounds are rendered once into small WAV files and played through SoundPool;
 *       they stay quiet when the phone is on silent or vibrate.</li>
 * </ul>
 */
public final class SoundEngine {

    private static final int RATE = 24000;
    private static final int BLOCK = 720; // 30 ms
    private static final float FADE_IN_SECONDS = 2.5f;
    /** Fading out also closes the master filter, so the music recedes rather than stops. */
    private static final float FADE_OUT_SECONDS = 1.6f;
    /** How long a start waits for its breathing loop before playing without it. */
    private static final long BED_WAIT_MS = 4000;
    private static final String SFX_VERSION = "3";
    private static SoundEngine instance;

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private final Beds beds;
    private final ExecutorService bedLoader = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "NothingLauncher+ music bed"));
    private volatile Style style = Style.CALM;
    private volatile float volume = 0.5f;
    private volatile boolean wanted;
    private volatile boolean othersPlaying;
    private Thread thread;
    private Ambience ambience;
    private Style ambienceStyle;
    private short[] ambienceBed;
    /** The style whose loop was last asked for, and the last loaded. */
    private Style bedAsked;
    private volatile Bed bed;

    private SoundPool pool;
    private final Map<Sfx, Integer> sfxIds = new EnumMap<>(Sfx.class);
    private boolean sfxLoading;

    public static synchronized SoundEngine get(Context context) {
        if (instance == null) {
            instance = new SoundEngine(context.getApplicationContext());
        }
        return instance;
    }

    private SoundEngine(Context context) {
        this.context = context;
        beds = new Beds(context, RATE);
        audio = context.getSystemService(AudioManager.class);
        audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                othersPlaying = othersPlaying(configs);
            }
        }, main);
        othersPlaying = othersPlaying(audio.getActivePlaybackConfigurations());
    }

    /** Media, calls or assistants playing elsewhere (our own track is a "game" stream). */
    private static boolean othersPlaying(List<AudioPlaybackConfiguration> configs) {
        for (AudioPlaybackConfiguration config : configs) {
            int usage = config.getAudioAttributes().getUsage();
            if (usage == AudioAttributes.USAGE_MEDIA
                    || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                    || usage == AudioAttributes.USAGE_ASSISTANT
                    || usage == AudioAttributes.USAGE_UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------------------
    // Music
    // -------------------------------------------------------------------------------------

    /** Plays (or keeps playing, switching style and volume) the background music. */
    public void playMusic(Style style, float volume) {
        this.style = style;
        this.volume = Math.max(0, Math.min(1, volume));
        synchronized (lock) {
            wanted = true;
            if (thread == null) {
                thread = new Thread(this::musicLoop, "NothingLauncher+ music");
                thread.start();
            }
        }
    }

    /** Fades the music out; the audio thread and track are released once silent. */
    public void stopMusic() {
        wanted = false;
    }

    public boolean isMusicPlaying() {
        return wanted;
    }

    /** A style and its breathing loop (null when Nothing Widgets can't give it). */
    private static final class Bed {
        final Style style;
        final short[] pcm;

        Bed(Style style, short[] pcm) {
            this.style = style;
            this.pcm = pcm;
        }
    }

    /** Starts decoding {@code wanted}'s breathing loop, unless it is loaded or on its way. */
    private void askBed(Style wanted) {
        if (bedAsked == wanted) {
            return;
        }
        bedAsked = wanted;
        bedLoader.execute(() -> bed = new Bed(wanted, beds.get(wanted)));
    }

    private void musicLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track = null;
        try {
            // The first start waits (silently, off the main thread) for its loop to decode.
            askBed(style);
            long waitUntil = System.currentTimeMillis() + BED_WAIT_MS;
            while (ambience == null && (bed == null || bed.style != style) && wanted
                    && System.currentTimeMillis() < waitUntil) {
                Thread.sleep(20);
            }
            // A short buffer (about four blocks) so a fade-out is heard the moment it starts.
            int minBuffer = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minBuffer, BLOCK * 2 * 4 * 4))
                    .build();
            track.play();
            float[] block = new float[BLOCK * 2];
            float gain = 0;
            float fadeIn = BLOCK / (FADE_IN_SECONDS * RATE);
            float fadeOut = BLOCK / (FADE_OUT_SECONDS * RATE);
            while (true) {
                Style current = style;
                askBed(current);
                Bed loaded = bed;
                boolean ready = loaded != null && loaded.style == current;
                if (ambience == null) {
                    ambience = new Ambience(RATE, current, System.nanoTime());
                    ambienceBed = ready ? loaded.pcm : null;
                    ambience.setStyle(current, ambienceBed);
                    ambienceStyle = current;
                }
                // A new style, or its loop arriving late: fade out, switch, fade back in.
                boolean switching = ambienceStyle != current
                        || (ready && ambienceBed != loaded.pcm);
                if (switching && ready && gain == 0) {
                    ambienceBed = loaded.pcm;
                    ambience.setStyle(current, ambienceBed);
                    ambienceStyle = current;
                    switching = false;
                }
                boolean playing = wanted && !othersPlaying && !switching;
                float target = playing ? volume : 0;
                float next = target > gain ? Math.min(target, gain + fadeIn)
                        : Math.max(target, gain - fadeOut);
                synchronized (lock) {
                    if (!wanted && next == 0) {
                        thread = null;
                        break;
                    }
                }
                // Receding: muffle in step with the fade, back to clear as it returns.
                ambience.setMuffle(playing ? 0 : 1 - next / Math.max(0.01f, volume));
                ambience.render(block, BLOCK);
                float step = (next - gain) / BLOCK;
                for (int i = 0; i < BLOCK; i++) {
                    gain += step;
                    float g = gain * gain; // Perceptual fade curve.
                    block[2 * i] *= g;
                    block[2 * i + 1] *= g;
                }
                gain = next;
                track.write(block, 0, block.length, AudioTrack.WRITE_BLOCKING);
            }
        } catch (Throwable t) {
            synchronized (lock) {
                thread = null;
            }
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // Never started.
                }
                track.release();
            }
        }
        // Asked to play again while we were finishing: start a fresh thread.
        synchronized (lock) {
            if (wanted && thread == null) {
                thread = new Thread(this::musicLoop, "NothingLauncher+ music");
                thread.start();
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Interface sounds
    // -------------------------------------------------------------------------------------

    /** Plays {@code sfx}; quiet on silent or vibrate, and until the sounds are prepared. */
    public void play(Sfx sfx, float volume) {
        if (audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            return;
        }
        synchronized (sfxIds) {
            Integer id = sfxIds.get(sfx);
            if (pool != null && id != null) {
                pool.play(id, volume, volume, 1, 0, 1);
                return;
            }
        }
        prepareSounds();
    }

    /** Renders and loads the interface sounds in the background (once). */
    public void prepareSounds() {
        synchronized (sfxIds) {
            if (sfxLoading) {
                return;
            }
            sfxLoading = true;
        }
        new Thread(this::loadSfx, "NothingLauncher+ sfx").start();
    }

    private void loadSfx() {
        SoundPool soundPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        File dir = new File(context.getCacheDir(), "nlplus_sfx_" + SFX_VERSION);
        dir.mkdirs();
        Map<Sfx, Integer> ids = new EnumMap<>(Sfx.class);
        for (Sfx sfx : Sfx.values()) {
            File file = new File(dir, sfx.name().toLowerCase() + ".wav");
            try {
                if (!file.exists()) {
                    writeWav(file, sfx.render());
                }
                ids.put(sfx, soundPool.load(file.getPath(), 1));
            } catch (IOException | RuntimeException e) {
                // Skip this sound; the others still work.
            }
        }
        synchronized (sfxIds) {
            pool = soundPool;
            sfxIds.putAll(ids);
        }
    }

    private static void writeWav(File file, short[] pcm) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            int bytes = pcm.length * 2;
            byte[] header = new byte[44];
            putAscii(header, 0, "RIFF");
            putInt(header, 4, 36 + bytes);
            putAscii(header, 8, "WAVEfmt ");
            putInt(header, 16, 16);
            putShort(header, 20, 1);
            putShort(header, 22, 1);
            putInt(header, 24, (int) Sfx.RATE);
            putInt(header, 28, (int) Sfx.RATE * 2);
            putShort(header, 32, 2);
            putShort(header, 34, 16);
            putAscii(header, 36, "data");
            putInt(header, 40, bytes);
            out.write(header);
            byte[] data = new byte[bytes];
            for (int i = 0; i < pcm.length; i++) {
                data[2 * i] = (byte) pcm[i];
                data[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            out.write(data);
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("rename failed");
        }
    }

    private static void putAscii(byte[] b, int at, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[at + i] = (byte) s.charAt(i);
        }
    }

    private static void putInt(byte[] b, int at, int v) {
        for (int i = 0; i < 4; i++) {
            b[at + i] = (byte) (v >> (8 * i));
        }
    }

    private static void putShort(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }
}
