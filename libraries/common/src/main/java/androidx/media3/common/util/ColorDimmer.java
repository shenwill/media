package androidx.media3.common.util;

import android.graphics.Bitmap;

public class ColorDimmer {

    public static final int MAX_OPACITY_LEVEL = 16;
    public static final double PEAK_NIT = 1000;
    public static final double TARGET_NIT_MIN = 50;
    public static final double TARGET_NIT_MAX = 550;
    public static final double STANDARD_TARGET_GAMMA = 2.2;

    // Perceptual Quantizer (ST.2084 / PQ) Constants
    private static final double M1 = 2610.0 / 16384.0;
    private static final double M2 = (2523.0 / 4096.0) * 128.0;
    private static final double C1 = 3424.0 / 4096.0;
    private static final double C2 = (2413.0 / 4096.0) * 32.0;
    private static final double C3 = (2392.0 / 4096.0) * 32.0;

    public static int dimByAlpha(int color, int opacityLevel) {
        if (opacityLevel >= 0 && opacityLevel < MAX_OPACITY_LEVEL) {
            int alpha = (color >> 24) & 0xFF;
            alpha = (alpha * opacityLevel) >> 4; // MAX_OPACITY_LEVEL is 16
            color = (alpha << 24) | (color & 0xffffff);
        }
        return color;
    }

    public static int dimByAttenuateLuminance(int argb, int opacityLevel) {
        return ColorDimmer.attenuateLuminance(argb, opacityLevel / 16f);
    }

    public static int dimByToneMapSdrToHdr(int argb, int opacityLevel) {
        double targetNits = TARGET_NIT_MIN + (opacityLevel * (TARGET_NIT_MAX - TARGET_NIT_MIN) / 16f);
        return ColorDimmer.toneMapSdrToHdr(argb, targetNits, PEAK_NIT, STANDARD_TARGET_GAMMA);
    }

    /**
     * 1. SDR-to-HDR Graphic Tone Mapping (BT.2408 Reference Scaling)
     * Scales subtitle luminance from SDR baseline relative to HDR peak brightness.
     *
     * @param argb         Input SDR subtitle RGBA color
     * @param targetNits   Target reference white (e.g., 100 to 203 nits)
     * @param hdrPeakNits  Peak luminance of display/content (e.g., 1000 nits)
     * @param gamma        Standard target gamma (e.g., 2.2)
     */
    public static int toneMapSdrToHdr(int argb, double targetNits, double hdrPeakNits, double gamma) {

        double scaleFactor = targetNits / hdrPeakNits;

        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        if (a == 0) {
            return 0;
        }

        // Convert sRGB non-linear to linear space
        double rLinear = Math.pow(r / 255.0, gamma);
        double gLinear = Math.pow(g / 255.0, gamma);
        double bLinear = Math.pow(b / 255.0, gamma);

        // Compress linear luminance factor
        double rScaled = rLinear * scaleFactor;
        double gScaled = gLinear * scaleFactor;
        double bScaled = bLinear * scaleFactor;

        // Re-encode to target gamma space
        int rOut = (int) Math.min(255, Math.round(Math.pow(rScaled, 1.0 / gamma) * 255.0));
        int gOut = (int) Math.min(255, Math.round(Math.pow(gScaled, 1.0 / gamma) * 255.0));
        int bOut = (int) Math.min(255, Math.round(Math.pow(bScaled, 1.0 / gamma) * 255.0));

        return (a << 24) | (rOut << 16) | (gOut << 8) | bOut;
    }

    /**
     * Maps absolute linear luminance in nits (0.0 to 10,000.0) into standard PQ code value [0.0, 1.0].
     */
    public static double linearNitsToPq(double nits) {
        double y = Math.max(0.0, nits / 10000.0);
        if (y == 0.0) return 0.0;
        double ym1 = Math.pow(y, M1);
        return Math.pow((C1 + C2 * ym1) / (1.0 + C3 * ym1), M2);
    }

    /**
     * Alpha Channel & Luma Attenuation (Linear Gain Reduction)
     */
    public static int attenuateLuminance(int argb, float dimFactor) {

        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        float factor = Math.max(0.0f, Math.min(1.0f, dimFactor));
        int rOut = Math.round(r * factor);
        int gOut = Math.round(g * factor);
        int bOut = Math.round(b * factor);

        return (a << 24) | (rOut << 16) | (gOut << 8) | bOut;
    }

    /**
     * Adaptive Picture Level (APL) Subtitle Dimming
     * Evaluates current video frame brightness and scales subtitle luminance accordingly.
     */
    public static int adaptToFrameAPL(
        int argb,
        Bitmap videoFrame,
        double minNits,
        double maxNits,
        double hdrPeakNits,
        double kFactor) {

        double apl = calculateFrameAPL(videoFrame); // Range [0.0, 1.0]

        // Non-linear target luminance response based on frame APL
        double targetNits = minNits + (maxNits - minNits) * Math.pow(apl, kFactor);

        return toneMapSdrToHdr(argb, targetNits, hdrPeakNits, 2.2);
    }

    /**
     * Calculates normalized Average Picture Level (APL) using ITU-R BT.709 luma weights.
     */
    public static double calculateFrameAPL(Bitmap frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        long totalLuma = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.getPixel(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                totalLuma += (long) luma;
            }
        }
        return (double) totalLuma / (width * height * 255.0);
    }
}