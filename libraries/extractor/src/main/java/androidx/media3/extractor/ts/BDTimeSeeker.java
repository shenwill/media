package androidx.media3.extractor.ts;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;

public class BDTimeSeeker {

    private final long durationUs;
    private final int timeUnitMs;
    private final long[] positions;

    public BDTimeSeeker(byte[] timeMapTableBytes, long durationUs) {
        ParsableByteArray ba = new ParsableByteArray(timeMapTableBytes);
        timeUnitMs = ba.readInt();
        assert timeMapTableBytes.length % 4 == 0;
        positions = new long[timeMapTableBytes.length / 4 - 1];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = ba.readInt();
        }
        this.durationUs = durationUs != C.TIME_UNSET ? durationUs
            : timeUnitMs * positions.length * 1000;
    }

    public SeekMap getSeekMap() {
        return new SeekMap() {
            @Override
            public boolean isSeekable() {
                return true;
            }

            @Override
            public long getDurationUs() {
                return durationUs;
            }

            @Override
            public SeekPoints getSeekPoints(long timeUs) {
                return new SeekPoints(new SeekPoint(timeUs, getSeekPosition(timeUs)));
            }

            private long getSeekPosition(long timeUs) {
                int timeMs = (int) (timeUs / 1000);
                int index = timeMs / timeUnitMs;
                index = Math.min(index, positions.length - 1);
                // android.util.Log.i("BDTimeSeeker", "---===getSeekPoints timeUs="
                //    + Util.timeString(timeUs / 1000) + " ms=" + timeMs
                //    + " index=" + index + "/" + (positions.length - 1)
                //    + " position=" + positions[index] * 192);
                return positions[index] * 192;
            }
        };
    }
}
