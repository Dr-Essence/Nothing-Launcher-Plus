package com.dressence.nothinglauncherplus.wallpaper;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * A sound file as a loop with no gap. Replayed as a file (MediaPlayer's looping), an MP3
 * stops at its end, its encoder padding and trailing silence play, and it starts again: a
 * pause at every turn. Here the file is decoded once to PCM, the silence at both ends cut off,
 * the last moments cross-faded into the start, and the result played by an AudioTrack looping
 * over its own buffer (setLoopPoints), so the end runs into the start sample for sample.
 */
final class SeamlessLoop {

    /** Below this (about -40 dBFS) a sample counts as silence at the ends. */
    private static final int SILENCE = 330;
    private static final int CROSSFADE_MS = 300;

    private SeamlessLoop() {
    }

    /** The decoded, trimmed, cross-faded loop: PCM 16-bit, its rate and channels. */
    static final class Pcm {
        final short[] samples;
        final int rate;
        final int channels;
        /** For the log: what was cut and how long the loop is. */
        String report = "";

        Pcm(short[] samples, int rate, int channels) {
            this.samples = samples;
            this.rate = rate;
            this.channels = channels;
        }
    }

    /** Decodes {@code file} and makes it loop with no gap (any thread but the main one). */
    static Pcm prepare(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getPath());
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0) {
            extractor.release();
            throw new IllegalArgumentException("no audio in " + file);
        }
        extractor.selectTrack(track);
        MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        ShortBuffer out = ShortBuffer.allocate(rate * channels * 30);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        try {
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
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
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat now = codec.getOutputFormat();
                    rate = now.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = now.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        ShortBuffer pcm = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                        if (out.remaining() < pcm.remaining()) {
                            ShortBuffer bigger = ShortBuffer.allocate(
                                    (out.capacity() + pcm.remaining()) * 2);
                            out.flip();
                            bigger.put(out);
                            out = bigger;
                        }
                        out.put(pcm);
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            codec.stop();
            codec.release();
            extractor.release();
        }
        short[] all = new short[out.position()];
        out.flip();
        out.get(all);
        int[] cut = new int[2];
        Pcm pcm = new Pcm(loop(all, channels, rate, cut), rate, channels);
        pcm.report = "file " + ms(all.length / channels, rate) + " ms, silence cut "
                + ms(cut[0], rate) + " ms at the start and " + ms(cut[1], rate)
                + " ms at the end, loop " + ms(pcm.samples.length / channels, rate) + " ms";
        return pcm;
    }

    /** The samples between the silences at the ends, the last moments faded into the start. */
    private static long ms(int frames, int rate) {
        return frames * 1000L / rate;
    }

    private static short[] loop(short[] all, int channels, int rate, int[] cut) {
        int frames = all.length / channels;
        int first = 0;
        while (first < frames && quiet(all, first, channels)) {
            first++;
        }
        int last = frames - 1;
        while (last > first && quiet(all, last, channels)) {
            last--;
        }
        int length = last - first + 1;
        cut[0] = first;
        cut[1] = frames - 1 - last;
        if (length <= 0) {
            return all; // All quiet: as it is.
        }
        int fade = Math.min(rate * CROSSFADE_MS / 1000, length / 4);
        int loopFrames = length - fade;
        short[] loop = new short[loopFrames * channels];
        System.arraycopy(all, first * channels, loop, 0, loopFrames * channels);
        // The loop's start: the start fading in under the end fading out, so the loop's end
        // (just before the faded part) runs on into it.
        for (int i = 0; i < fade; i++) {
            float in = (float) i / fade;
            for (int c = 0; c < channels; c++) {
                int head = (first + i) * channels + c;
                int tail = (first + loopFrames + i) * channels + c;
                float mixed = all[head] * in + all[tail] * (1 - in);
                loop[i * channels + c] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(mixed)));
            }
        }
        return loop;
    }

    private static boolean quiet(short[] all, int frame, int channels) {
        for (int c = 0; c < channels; c++) {
            if (Math.abs(all[frame * channels + c]) > SILENCE) {
                return false;
            }
        }
        return true;
    }

    /** An AudioTrack looping {@code pcm} forever, silent until its volume is raised. */
    static AudioTrack track(Pcm pcm) {
        int mask = pcm.channels == 1 ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int bytes = pcm.samples.length * 2;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.rate)
                        .setChannelMask(mask).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build();
        track.write(pcm.samples, 0, pcm.samples.length);
        track.setLoopPoints(0, pcm.samples.length / pcm.channels, -1);
        track.setVolume(0f);
        return track;
    }
}
