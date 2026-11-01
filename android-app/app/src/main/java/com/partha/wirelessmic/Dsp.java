package com.partha.wirelessmic;

/** Audio processing: spectral noise suppression with 80 Hz low-cut, and a noise gate. */
final class Dsp {
    static final int RATE = 48000;
    static final int HOP = 512;                 // ~10.7 ms per frame
    static final int N = 2 * HOP;               // 1024-point FFT, 50 % overlap

    /** In-place iterative radix-2 complex FFT. */
    static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    /**
     * Minimum-statistics spectral noise suppression. sqrt-Hann analysis/synthesis windows at
     * 50 % overlap give perfect reconstruction; the noise floor per bin follows the quietest
     * recent level (drops fast, rises ~0.5 dB/s) so steady hiss/fan/AC noise is subtracted.
     */
    static final class NoiseSuppressor {
        private final float[] win = new float[N], prevIn = new float[HOP], prevOut = new float[HOP];
        private final float[] re = new float[N], im = new float[N];
        private final float[] psd = new float[N / 2 + 1], noise = new float[N / 2 + 1], gain = new float[N / 2 + 1];
        private final int hpfBins = (int) (80 / ((float) RATE / N)) + 1;
        private boolean init;

        NoiseSuppressor() {
            for (int i = 0; i < N; i++) win[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
            java.util.Arrays.fill(gain, 1f);
        }

        /** level: 0 off, 1 low, 2 high. x and out are HOP samples. */
        void process(float[] x, float[] out, int level, boolean hpf) {
            for (int i = 0; i < HOP; i++) { re[i] = prevIn[i] * win[i]; re[i + HOP] = x[i] * win[i + HOP]; }
            System.arraycopy(x, 0, prevIn, 0, HOP);
            java.util.Arrays.fill(im, 0f);
            fft(re, im, false);
            float over = level == 2 ? 3.0f : 1.6f, floor = level == 2 ? 0.06f : 0.18f;
            for (int k = 0; k <= N / 2; k++) {
                float p = re[k] * re[k] + im[k] * im[k];
                psd[k] = 0.8f * psd[k] + 0.2f * p;
                if (!init) noise[k] = psd[k] + 1e-3f;
                noise[k] = Math.min(noise[k] * 1.0012f, Math.max(psd[k], 1e-3f));
                float g = 1f;
                if (level > 0) g = Math.max(floor, Math.min(1f, 1f - over * noise[k] / (psd[k] + 1e-9f)));
                gain[k] = 0.55f * gain[k] + 0.45f * g;
                float gk = (hpf && k < hpfBins) ? 0f : gain[k];
                re[k] *= gk; im[k] *= gk;
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }   // keep spectrum Hermitian
            }
            init = true;
            fft(re, im, true);
            for (int i = 0; i < HOP; i++) out[i] = prevOut[i] + re[i] * win[i];
            for (int i = 0; i < HOP; i++) prevOut[i] = re[i + HOP] * win[i + HOP];
        }
    }

    /** Hysteresis noise gate with a per-frame gain ramp (no clicks). */
    static final class Gate {
        private final float openT = (float) Math.pow(10, -48 / 20.0), closeT = (float) Math.pow(10, -54 / 20.0);
        private float g;
        private boolean open;

        void process(float[] x) {
            double s = 0;
            for (float v : x) s += v * v;
            float level = (float) Math.sqrt(s / x.length) / 32768f;
            if (level > openT) open = true; else if (level < closeT) open = false;
            float target = open ? 1f : 0.05f;
            for (int i = 0; i < x.length; i++) x[i] *= g + (target - g) * i / x.length;
            g = target;
        }
    }

    private Dsp() {}
}
