package androidx.media3.extractor.rmvb;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Facilitates the extraction of data from the real container format.
 */
public final class RmvbExtractor implements Extractor {

   private final String TAG = RmvbExtractor.class.getSimpleName();

   /** Factory that returns one extractor which is a {@link RmvbExtractor}. */
   public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new RmvbExtractor()};


   private final ParsableByteArray outputBuffer;
   private @MonotonicNonNull ExtractorOutput extractorOutput;
   private @MonotonicNonNull TrackOutput trackOutput;

   private MediaContainerDemuxer mDemuxer;

   public RmvbExtractor() {
      outputBuffer = new ParsableByteArray();
      mDemuxer = new MediaContainerDemuxer();
   }

   public void setUrl(String url) {
      mDemuxer.setUrl(url);
   }

   @Override
   public void init(ExtractorOutput output) {
      extractorOutput = output;
      trackOutput = extractorOutput.track(0, C.TRACK_TYPE_VIDEO);
      extractorOutput.endTracks();
      mDemuxer.init(output);
   }

   @Override
   public boolean sniff(ExtractorInput input) throws IOException {
      final byte[] RM_TAG = {'.', 'R', 'M', 'F'};
      byte[] head = new byte[4];
      input.peek(head, 0, 4);
      return Arrays.equals(head, RM_TAG);
   }

   @Override
   public int read(final ExtractorInput input, PositionHolder seekPosition)
       throws IOException {
      int rt = mDemuxer.prepare(input);
      if (rt != 0)
         return Extractor.RESULT_END_OF_INPUT;
      return Extractor.RESULT_CONTINUE;
   }

   @Override
   public void seek(long position, long timeUs) {
   }

   @Override
   public void release() {
   }
}
