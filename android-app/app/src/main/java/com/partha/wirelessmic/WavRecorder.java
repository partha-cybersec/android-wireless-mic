package com.partha.wirelessmic;

import android.content.Context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Stereo 48 kHz / 16-bit WAV: left = main, right = safety track at -6 dB.
 * The header is rewritten every second so a crash or flat battery still leaves a playable file.
 */
final class WavRecorder {
    private RecStore.Output out;
    private String folder;
    private long frames;
    private ByteBuffer buf = ByteBuffer.allocate(0);

    /** suffix: "MIC" on the transmitter, "RX" on the receiver, so files from both phones are easy to tell apart. */
    synchronized void start(Context c, String suffix) throws IOException {
        if (out != null) return;
        String name = new SimpleDateFormat("'WMIC_'yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_" + suffix + ".wav";
        out = RecStore.create(c, name);
        folder = RecStore.describe(c);
        out.channel.write(ByteBuffer.wrap(header(0)));
        frames = 0;
    }

    synchronized void write(float[] main) {
        if (out == null) return;
        if (buf.capacity() != main.length * 4) buf = ByteBuffer.allocate(main.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        for (float v : main) { buf.putShort(clip(v)); buf.putShort(clip(v * 0.5f)); }
        buf.flip();
        try {
            while (buf.hasRemaining()) out.channel.write(buf);
            long before = frames;
            frames += main.length;
            if (before / Dsp.RATE != frames / Dsp.RATE) fixHeader();
        } catch (IOException e) {
            stop();
        }
    }

    synchronized void writePcm(short[] pcm) {
        float[] f = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) f[i] = pcm[i];
        write(f);
    }

    synchronized void stop() {
        if (out == null) return;
        try { fixHeader(); } catch (IOException ignored) { }
        try { out.close(); } catch (IOException ignored) { }
        out = null;
    }

    synchronized boolean active() { return out != null; }
    synchronized String name() { return out == null ? null : out.name; }
    synchronized String folder() { return folder; }
    synchronized double seconds() { return out == null ? 0 : frames / (double) Dsp.RATE; }

    private void fixHeader() throws IOException {
        long pos = out.channel.position();
        out.channel.write(ByteBuffer.wrap(header(frames)), 0);
        out.channel.position(pos);
        out.channel.force(false);
    }

    private static short clip(float v) { return (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v)); }

    private static byte[] header(long n) {
        long data = n * 4;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVEfmt ".getBytes())
         .putInt(16).putShort((short) 1).putShort((short) 2).putInt(Dsp.RATE).putInt(Dsp.RATE * 4)
         .putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt((int) data);
        return b.array();
    }
}
