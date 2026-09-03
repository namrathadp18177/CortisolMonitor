package com.example.myapplication;

import java.util.List;

/**
 * Computes standard time-domain HRV metrics from a chronological list of valid inter-beat
 * intervals (IBI / RR intervals, milliseconds), as delivered by Samsung's
 * HEART_RATE_CONTINUOUS tracker (see WatchHealthManager.kt in the watch module). Only
 * ibiStatus == 0 values should be passed in — that filtering happens in
 * {@link VitalsDatabaseHelper}'s IBI-series query, before this class is ever called.
 *
 * Formulas (standard time-domain HRV — see Shaffer &amp; Ginsberg 2017):
 * <pre>
 *   meanIbiMs    = mean of all IBI values
 *   meanHrBpm    = 60000 / meanIbiMs
 *   sdnnMs       = standard deviation of all IBI values
 *   rmssdMs      = sqrt(mean((IBI[i+1] - IBI[i])^2)) over successive differences
 *   pnn50Percent = % of successive differences with |diff| &gt; 50 ms
 * </pre>
 *
 * NOTE: IBI values are batched per 1-second HEART_RATE_CONTINUOUS sample (up to 4 values per
 * sample) and concatenated here in arrival order across a session/day. That's the standard
 * way to reconstruct an RR-interval series from this tracker, but it is not a perfectly
 * gapless beat-to-beat trace — a dropped/invalid sample (status != 0) leaves a small gap.
 * Fine for RMSSD/SDNN over minutes-to-hours; treat short (&lt;2 min) windows with more caution,
 * see {@link HrvMetrics#hasEnoughData()}.
 */
public final class HrvCalculator {

    private HrvCalculator() {}

    /** One session/day's worth of computed HRV metrics. */
    public static class HrvMetrics {
        public final int beatCount;      // number of valid IBI values used (NN count)
        public final double meanIbiMs;
        public final double meanHrBpm;
        public final double sdnnMs;
        public final double rmssdMs;
        public final double pnn50Percent;

        HrvMetrics(int beatCount, double meanIbiMs, double meanHrBpm,
                   double sdnnMs, double rmssdMs, double pnn50Percent) {
            this.beatCount = beatCount;
            this.meanIbiMs = meanIbiMs;
            this.meanHrBpm = meanHrBpm;
            this.sdnnMs = sdnnMs;
            this.rmssdMs = rmssdMs;
            this.pnn50Percent = pnn50Percent;
        }

        /** True once there are enough beats for RMSSD/SDNN to be reasonably stable — rule of
         *  thumb of at least ~30 NN intervals (roughly 30+ seconds of continuous beats). Below
         *  this, still show the numbers but the UI should flag them as low-confidence. */
        public boolean hasEnoughData() {
            return beatCount >= 30;
        }
    }

    /** Returns null if fewer than 2 IBI values are given (need at least 2 for one successive
     *  difference — RMSSD/pNN50 are undefined below that). Callers should also check
     *  {@link HrvMetrics#hasEnoughData()} before treating a result as meaningful. */
    public static HrvMetrics compute(List<Integer> ibiMs) {
        if (ibiMs == null || ibiMs.size() < 2) return null;

        int n = ibiMs.size();
        double sum = 0;
        for (int v : ibiMs) sum += v;
        double mean = sum / n;

        double sqDiffSum = 0;
        for (int v : ibiMs) {
            double d = v - mean;
            sqDiffSum += d * d;
        }
        double sdnn = Math.sqrt(sqDiffSum / n);

        double succDiffSqSum = 0;
        int nn50Count = 0;
        for (int i = 1; i < n; i++) {
            double diff = ibiMs.get(i) - ibiMs.get(i - 1);
            succDiffSqSum += diff * diff;
            if (Math.abs(diff) > 50) nn50Count++;
        }
        double rmssd = Math.sqrt(succDiffSqSum / (n - 1));
        double pnn50 = (100.0 * nn50Count) / (n - 1);

        double meanHr = mean > 0 ? 60000.0 / mean : 0;

        return new HrvMetrics(n, mean, meanHr, sdnn, rmssd, pnn50);
    }
}
