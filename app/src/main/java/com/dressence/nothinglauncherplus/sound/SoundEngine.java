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

/**
 * Plays the background music and interface sounds in one process (the launcher, or the module
 * app for previews).
 *
 * <ul>
 *   <li>Music is synthesized live, in stereo, on one audio thread, and only while asked to
 *       play: the launcher asks while it is in front and lets go the moment an app is tapped.
 *       Starts fade in (after a long silence, with a console-style opening swell); stops
 *       recede over 1.6 s, quieter and more muffled, through a short buffer so the fade is
 *       heard at once.</li>
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
    /** After this long without music, the next start opens with the console-style swell. */
    private static final long INTRO_AFTER_SILENCE_MS = 10 * 60 * 1000;
    private static final String SFX_VERSION = "2";
    private static SoundEngine instance;

    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private volatile Style style = Style.MEMORY_CARD;
    private volatile float volume = 0.5f;
    private volatile boolean wanted;
    private volatile boolean othersPlaying;
    private Thread thread;
    private Synth synth;
    private Style synthStyle;
    private long silentSince;

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

    private void musicLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track = null;
        try {
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
            boolean intro = silentSince == 0
                    || System.currentTimeMillis() - silentSince > INTRO_AFTER_SILENCE_MS;
            while (true) {
                Style current = style;
                if (synth == null) {
                    synth = new Synth(RATE, current, System.nanoTime());
                    synthStyle = current;
                } else if (synthStyle != current) {
                    synth.setStyle(current);
                    synthStyle = current;
                }
                if (intro) {
                    synth.intro();
                    intro = false;
                }
                boolean playing = wanted && !othersPlaying;
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
                synth.setMuffle(playing ? 0 : 1 - next / Math.max(0.01f, volume));
                synth.render(block, BLOCK);
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
            silentSince = System.currentTimeMillis();
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
