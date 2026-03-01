package androidx.media3.extractor.ts;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;

public class DvdTimeSeeker {

    private final long durationUs;
    private final boolean fps30;
    private final int timeUnit;
    private final long[] positions;

    public DvdTimeSeeker(byte[] timeMapTableBytes, long durationUs) {
        ParsableByteArray ba = new ParsableByteArray(timeMapTableBytes);
        timeUnit = ba.readUnsignedByte();
        fps30 = ba.readUnsignedByte() != 0;
        assert (timeMapTableBytes.length - 2) % 8 == 0;
        positions = new long[(timeMapTableBytes.length - 2) / 8];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = ba.readLong();
        }
        this.durationUs = durationUs != C.TIME_UNSET ? durationUs
            : timeUnit * positions.length * C.MICROS_PER_SECOND;
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
                int timeSeconds = (int) (timeUs / C.MICROS_PER_SECOND);
                timeSeconds = fps30 ? timeSeconds * 1000 / 1001 : timeSeconds;
                int index = timeSeconds / timeUnit;
                index = Math.min(index, positions.length - 1);
                android.util.Log.i("DvdTimeSeeker", "```getSeekPoints timeUs="
                    + Util.timeString(timeUs / 1000) + " seconds=" + timeSeconds
                    + " index=" + index + " position=" + positions[index]);
                return positions[index];
            }
        };
    }
}
