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
     * Speech-aware spectral noise suppression.
     * - Noise PSD tracked with speech-presence probability (Gerkmann &amp; Hendriks, "unbiased MMSE"):
     *   bins where speech is likely barely update the noise estimate, so voices are not learned as noise
     *   while changing background noise (fans, traffic) is followed within ~1 s.
     * - Gain: Wiener filter on the decision-directed a-priori SNR (Ephraim &amp; Malah), which keeps the
     *   residual noise smooth instead of "musical".
     * - Optional dual-mic stage: near-field speech is much louder at the primary mic than at the
     *   reference mic, diffuse background noise is not, so the per-bin power level difference (PLD)
     *   gives a second, direction-based gain.
     * sqrt-Hann analysis/synthesis at 50 % overlap = perfect reconstruction, 10.7 ms extra latency.
     */
    static final class NoiseSuppressor {
        private static final int B = N / 2 + 1;
        private final float[] win = new float[N], prevIn = new float[HOP], prevIn2 = new float[HOP], prevOut = new float[HOP];
        private final float[] re = new float[N], im = new float[N], re2 = new float[N], im2 = new float[N];
        private final float[] noise = new float[B], gain = new float[B], prevClean = new float[B], pBar = new float[B];
        private final float[] pld = new float[B], p1s = new float[B], p2s = new float[B];
        private final int hpfBins = (int) (80 / ((float) RATE / N)) + 1;
        private int frames;
        float noiseDb = -90, reductionDb;          // for the meter: residual noise level and attenuation applied

        NoiseSuppressor() {
            for (int i = 0; i < N; i++) win[i] = (float) Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
            java.util.Arrays.fill(gain, 1f);
        }

        void process(float[] x, float[] out, int level, boolean hpf) { process(x, null, out, level, hpf); }

        /** level: 0 off, 1 low, 2 high. ref = second mic (dual-mic mode) or null. All arrays are HOP samples. */
        void process(float[] x, float[] ref, float[] out, int level, boolean hpf) {
            for (int i = 0; i < HOP; i++) { re[i] = prevIn[i] * win[i]; re[i + HOP] = x[i] * win[i + HOP]; }
            System.arraycopy(x, 0, prevIn, 0, HOP);
            java.util.Arrays.fill(im, 0f);
            fft(re, im, false);
            if (ref != null) {
                for (int i = 0; i < HOP; i++) { re2[i] = prevIn2[i] * win[i]; re2[i + HOP] = ref[i] * win[i + HOP]; }
                System.arraycopy(ref, 0, prevIn2, 0, HOP);
                java.util.Arrays.fill(im2, 0f);
                fft(re2, im2, false);
            }
            // level presets: max attenuation (gain floor) and dual-mic strictness
            float gmin = level == 2 ? 0.05f : 0.16f;                 // -26 dB / -16 dB
            final float xiH1 = 31.6f, alphaDD = 0.98f;              // speech-present SNR prior (15 dB), DD smoothing
            double inSum = 0, outSum = 0, nSum = 0;
            frames++;
            for (int k = 0; k < B; k++) {
                float P = re[k] * re[k] + im[k] * im[k] + 1e-6f;
                if (frames <= 8) noise[k] = frames == 1 ? P : noise[k] + (P - noise[k]) / frames;   // initial noise = first ~85 ms
                float gamma = P / noise[k];
                // speech presence probability -> noise update (fast in noise-only bins, frozen in speech)
                float p = (float) (1.0 / (1.0 + (1 + xiH1) * Math.exp(-gamma * xiH1 / (1 + xiH1))));
                pBar[k] = 0.9f * pBar[k] + 0.1f * p;
                if (pBar[k] > 0.99f) p = Math.min(p, 0.99f);
                noise[k] = 0.8f * noise[k] + 0.2f * (p * noise[k] + (1 - p) * P);
                float g = 1f;
                if (level > 0) {
                    float xi = alphaDD * prevClean[k] / noise[k] + (1 - alphaDD) * Math.max(gamma - 1, 0);
                    // parametric Wiener: beta > 1 over-subtracts (stronger, slightly less natural)
                    float beta = level == 2 ? 4f : 1.5f;
                    g = Math.max(gmin, xi / (beta + xi));
                    if (ref != null) {                               // dual-mic power level difference
                        float P2 = re2[k] * re2[k] + im2[k] * im2[k] + 1e-6f;
                        p1s[k] = 0.7f * p1s[k] + 0.3f * P;
                        p2s[k] = 0.7f * p2s[k] + 0.3f * P2;
                        pld[k] = (p1s[k] - p2s[k]) / (p1s[k] + p2s[k]);
                        float lo = level == 2 ? 0.15f : 0.05f, hi = level == 2 ? 0.55f : 0.40f;
                        float gd = Math.max(gmin, Math.min(1f, (pld[k] - lo) / (hi - lo)));
                        g = Math.min(g, Math.max(gd, g * 0.5f + gmin));   // never more than ~6 dB beyond the Wiener gain
                    }
                }
                gain[k] = 0.6f * gain[k] + 0.4f * g;                // light temporal smoothing
                float gk = (hpf && k < hpfBins) ? 0f : gain[k];
                prevClean[k] = gk * gk * P;
                inSum += P; outSum += gk * gk * P; nSum += noise[k];
                re[k] *= gk; im[k] *= gk;
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }   // keep spectrum Hermitian
            }
            noiseDb = (float) (10 * Math.log10(nSum / (N * N / 4.0) + 1e-12) - 90.3);
            reductionDb = (float) (10 * Math.log10((outSum + 1e-9) / (inSum + 1e-9)));
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
