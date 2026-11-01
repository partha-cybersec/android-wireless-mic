package com.partha.wirelessmic;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Stereo 48 kHz / 16-bit WAV: left = main, right = safety track at -6 dB. Header rewritten every second. */
final class WavRecorder {
    private RandomAccessFile f;
    private File file;
    private long frames;
    private byte[] buf = new byte[0];

    synchronized void start(File dir) throws IOException {
        if (f != null) return;
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        String name = new SimpleDateFormat("'WMIC_'yyyyMMdd_HHmmss'.wav'", Locale.US).format(new Date());
        file = new File(dir, name);
        f = new RandomAccessFile(file, "rw");
        f.setLength(0);
        f.write(header(0));
        frames = 0;
    }

    synchronized void write(float[] main) {
        if (f == null) return;
        if (buf.length != main.length * 4) buf = new byte[main.length * 4];
        for (int i = 0, o = 0; i < main.length; i++) {
            int l = clip(main[i]), r = clip(main[i] * 0.5f);
            buf[o++] = (byte) l; buf[o++] = (byte) (l >> 8);
            buf[o++] = (byte) r; buf[o++] = (byte) (r >> 8);
        }
        try {
            f.write(buf);
            long before = frames;
            frames += main.length;
            if (before / Dsp.RATE != frames / Dsp.RATE) fixHeader();
        } catch (IOException e) {
            stop();
        }
    }

    synchronized void stop() {
        if (f == null) return;
        try { fixHeader(); f.close(); } catch (IOException ignored) { }
        f = null;
    }

    synchronized boolean active() { return f != null; }
    synchronized String name() { return file == null ? null : file.getName(); }
    synchronized double seconds() { return f == null ? 0 : frames / (double) Dsp.RATE; }

    private void fixHeader() throws IOException {
        long pos = f.getFilePointer();
        f.seek(0);
        f.write(header(frames));
        f.seek(pos);
    }

    private static int clip(float v) { return v > 32767 ? 32767 : (v < -32768 ? -32768 : (int) v); }

    private static byte[] header(long n) {
        long data = n * 4;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt((int) (36 + data)).put("WAVEfmt ".getBytes())
         .putInt(16).putShort((short) 1).putShort((short) 2).putInt(Dsp.RATE).putInt(Dsp.RATE * 4)
         .putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt((int) data);
        return b.array();
    }
}
