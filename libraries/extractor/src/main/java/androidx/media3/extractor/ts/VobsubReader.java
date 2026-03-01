/*
 * Shen Wei
 */
package androidx.media3.extractor.ts;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import com.google.common.primitives.Ints;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/** Parses Vobsub subtitle data and extracts individual packages into frames. */
@UnstableApi
public final class VobsubReader implements ElementaryStreamReader {

  @Nullable
  private final String language;
  private @MonotonicNonNull TrackOutput output;

  private int bytesWritten;
  private int frameLength;
  private final ParsableByteArray leftOverBytes;
  private long sampleTimeUs;

  private final static String idx = "size: 720x480\npalette: FFFFFF, 7F7F7F, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000, 000000\n";
  private final static String idxTemplate = "size: %dx%d\npalette: %s\n";
  private static byte[] initData = idx.getBytes();
  private boolean trackCreated;

  public VobsubReader(@Nullable String language, int width, int height, byte[] paletteBytes) {
    bytesWritten = 0;
    frameLength = C.LENGTH_UNSET;
    this.language = language;
    leftOverBytes = new ParsableByteArray(new byte[2], 0);
    sampleTimeUs = C.TIME_UNSET;
    trackCreated = false;
    byte[] bytes = paletteBytes;
    if (width > 0 && height > 0 && bytes != null && bytes.length >= 4 * 16) {
      StringBuilder builder = new StringBuilder();
      for (int i = 0; i < 16; i++) {
        int p = 4 * i;
        int color = yuv2rgb(Ints.fromBytes(bytes[p], bytes[p + 1], bytes[p + 2], bytes[p + 3]));
        builder.append(String.format("%06X", color));
        builder.append(", ");
      }
      builder.delete(builder.length() - 2, builder.length());
      String s = String.format(idxTemplate, width, height, builder);
      initData = s.getBytes();
    }
  }

  @Override
  public void seek() {
    frameLength = C.LENGTH_UNSET;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    if (trackCreated) {
      return;
    }
    trackCreated = true;
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setSampleMimeType(MimeTypes.APPLICATION_VOBSUB)
            .setLanguage(language)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .setInitializationData(List.of(initData))
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (sampleTimeUs == C.TIME_UNSET) {
      sampleTimeUs = pesTimeUs;
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (sampleTimeUs == C.TIME_UNSET || data.bytesLeft() == 0) {
      return;
    }
    if (frameLength == C.LENGTH_UNSET) {
      if (leftOverBytes.bytesLeft() > 0) {
        assert leftOverBytes.getPosition() == 0 && leftOverBytes.capacity() >= 2;
        leftOverBytes.getData()[1] = (byte) data.readUnsignedByte();
        leftOverBytes.setLimit(2);
        frameLength = leftOverBytes.readUnsignedShort();
        leftOverBytes.setPosition(0);
        output.sampleData(leftOverBytes, 2);
        bytesWritten = 2;
      } else if (data.bytesLeft() >= 2) {
        frameLength = data.readUnsignedShort();
        data.setPosition(data.getPosition() - 2);
        bytesWritten = 0;
      } else {
        leftOverBytes.reset(0);
        leftOverBytes.getData()[0] = (byte) data.readUnsignedByte();
        leftOverBytes.setLimit(1);
        return;
      }
    }
    int bytesToWrite = Math.min(frameLength - bytesWritten, data.bytesLeft());
    output.sampleData(data, bytesToWrite);
    bytesWritten += bytesToWrite;
    if (frameLength == bytesWritten) {
      output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, bytesWritten, 0, null);
      bytesWritten = 0;
      sampleTimeUs = C.TIME_UNSET;
      frameLength = C.LENGTH_UNSET;
    }
    data.skipBytes(data.bytesLeft());
  }

  private int yuv2rgb(int yuv) {
    double y, Cr, Cb;
    int r, g, b;

    y  = (yuv >> 16) & 0xff;
    Cr = (yuv >>  8) & 0xff;
    Cb = (yuv      ) & 0xff;

    r = (int) (1.164 * (y - 16)                      + 1.596 * (Cr - 128));
    g = (int) (1.164 * (y - 16) - 0.392 * (Cb - 128) - 0.813 * (Cr - 128));
    b = (int) (1.164 * (y - 16) + 2.017 * (Cb - 128));

    r = (r < 0) ? 0 : r;
    g = (g < 0) ? 0 : g;
    b = (b < 0) ? 0 : b;

    r = (r > 255) ? 255 : r;
    g = (g > 255) ? 255 : g;
    b = (b > 255) ? 255 : b;

    return (r << 16) | (g << 8) | b;
  }
}
