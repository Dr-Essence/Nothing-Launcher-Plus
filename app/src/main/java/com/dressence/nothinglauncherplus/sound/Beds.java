package com.dressence.nothinglauncherplus.sound;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Reads a style's breathing loop out of Nothing Widgets ({@code com.nothing.hearthstone}, where
 * the Calm, Focus and Relax widgets keep them in {@code res/raw}) and decodes it to 16-bit
 * interleaved stereo at the music's rate. Holds the last one decoded; anything missing or
 * unreadable gives null, and the music breathes on its own instead.
 */
final class Beds {

    private static final String WIDGETS = "com.nothing.hearthstone";
    private static final long TIMEOUT_US = 10_000;
    /** Taps each side of the resampling filter. */
    private static final int TAPS = 16;
    /** Fractional positions the resampling weights are worked out for. */
    private static final int PHASES = 256;
    /** A decoder that never reaches its end is given up on after this many turns. */
    private static final int MAX_TURNS = 20_000;

    private final Context context;
    private final int rate;
    private Style cachedStyle;
    private short[] cached;

    Beds(Context context, int rate) {
        this.context = context;
        this.rate = rate;
    }

    /** The bed for {@code style}; slow the first time (decodes), so never on the main thread. */
    synchronized short[] get(Style style) {
        if (style != cachedStyle) {
            cached = null; // Let the old one go before decoding the next.
            cached = decode(style);
            cachedStyle = style;
        }
        return cached;
    }

    private short[] decode(Style style) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            Resources res = context.createPackageContext(WIDGETS, 0).getResources();
            int id = res.getIdentifier(style.bed, "raw", WIDGETS);
            if (id == 0) {
                return null;
            }
            try (AssetFileDescriptor fd = res.openRawResourceFd(id)) {
                extractor.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(),
                        fd.getLength());
            }
            MediaFormat format = null;
            for (int t = 0; t < extractor.getTrackCount(); t++) {
                MediaFormat f = extractor.getTrackFormat(t);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(t);
                    format = f;
                    break;
                }
            }
            if (format == null) {
                return null;
            }
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            int sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean floats = false;
            ShortArray pcm = new ShortArray();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            for (int turn = 0; ; turn++) {
                if (turn > MAX_TURNS) {
                    return null;
                }
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    sourceRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floats = f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            == AudioFormat.ENCODING_PCM_FLOAT;
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset).limit(info.offset + info.size);
                        ByteBuffer bytes = buffer.slice().order(ByteOrder.nativeOrder());
                        if (floats) {
                            pcm.add(bytes.asFloatBuffer(), channels);
                        } else {
                            pcm.add(bytes.asShortBuffer(), channels);
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
            return pcm.size < 4 * TAPS ? null : resample(pcm.data, pcm.size, sourceRate, rate);
        } catch (Throwable t) {
            return null; // Nothing Widgets missing or the loop unreadable: no bed.
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                    // Never started.
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /**
     * The first {@code length} samples of a loop, from {@code from} Hz to {@code to} Hz:
     * windowed sinc, its weights precomputed for {@link #PHASES} fractional positions, wrapping
     * round the loop so its join stays seamless.
     */
    static short[] resample(short[] in, int length, int from, int to) {
        if (from == to) {
            return java.util.Arrays.copyOf(in, length);
        }
        int frames = length / 2;
        int outFrames = (int) Math.round((double) frames * to / from);
        double cutoff = Math.min(1, (double) to / from) * 0.92;
        float[][] table = new float[PHASES + 1][2 * TAPS];
        for (int ph = 0; ph <= PHASES; ph++) {
            double frac = (double) ph / PHASES;
            for (int j = 0; j < 2 * TAPS; j++) {
                double d = frac + TAPS - 1 - j; // Distance from x to tap k = centre - TAPS + 1 + j.
                double w = 0.5 + 0.5 * Math.cos(Math.PI * d / (TAPS + 1)); // Hann window.
                double arg = Math.PI * d * cutoff;
                double sinc = Math.abs(arg) < 1e-9 ? cutoff : Math.sin(arg) / (Math.PI * d);
                table[ph][j] = (float) (sinc * w);
            }
        }
        short[] out = new short[outFrames * 2];
        double step = (double) from / to;
        for (int n = 0; n < outFrames; n++) {
            double x = n * step;
            int centre = (int) Math.floor(x);
            float[] weights = table[(int) Math.round((x - centre) * PHASES)];
            float l = 0;
            float r = 0;
            int first = centre - TAPS + 1;
            for (int j = 0; j < 2 * TAPS; j++) {
                int k = first + j;
                int i = (k < 0 ? k + frames : k >= frames ? k - frames : k) * 2;
                l += in[i] * weights[j];
                r += in[i + 1] * weights[j];
            }
            out[2 * n] = clip(l);
            out[2 * n + 1] = clip(r);
        }
        return out;
    }

    private static short clip(float v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }

    /** A growing interleaved-stereo buffer (mono is doubled, extra channels dropped). */
    private static final class ShortArray {
        short[] data = new short[1 << 16];
        int size;

        void add(ShortBuffer samples, int channels) {
            int frames = samples.remaining() / Math.max(1, channels);
            grow(frames);
            for (int f = 0; f < frames; f++) {
                short left = samples.get(f * channels);
                short right = channels > 1 ? samples.get(f * channels + 1) : left;
                data[size++] = left;
                data[size++] = right;
            }
        }

        void add(FloatBuffer samples, int channels) {
            int frames = samples.remaining() / Math.max(1, channels);
            grow(frames);
            for (int f = 0; f < frames; f++) {
                float left = samples.get(f * channels);
                float right = channels > 1 ? samples.get(f * channels + 1) : left;
                data[size++] = clip(left * 32767);
                data[size++] = clip(right * 32767);
            }
        }

        private void grow(int frames) {
            if (size + frames * 2 > data.length) {
                short[] grown = new short[Math.max(data.length * 2, size + frames * 2)];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
        }
    }
}
