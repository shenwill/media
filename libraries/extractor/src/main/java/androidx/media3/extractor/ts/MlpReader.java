/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.ts;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkState;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.TrueHdSampleRechunker;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Parses a continuous MLP stream and extracts individual samples.
 * It's only for TrueHD right now, but can support MLP later.
 * */
@UnstableApi
public final class MlpReader implements ElementaryStreamReader {

  private final String TAG = "MlpReader";

  public static final class MajorSyncInfo {

    /**
     * MLP stream types.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef({STREAM_TYPE_UNDEFINED, STREAM_TYPE_TRUEHD, STREAM_TYPE_MLP})
    public @interface StreamType {}

    /** Undefined stream type. */
    public static final int STREAM_TYPE_UNDEFINED = -1;

    public static final int STREAM_TYPE_TRUEHD = 0xBA;

    public static final int STREAM_TYPE_MLP = 0xBB;

    @Nullable public final String mimeType;

    /**
     * The type of the stream if {@link #mimeType} is {@link MimeTypes#AUDIO_TRUEHD}, or {@link
     * #STREAM_TYPE_UNDEFINED} otherwise.
     */
    public final @MajorSyncInfo.StreamType int streamType;

    /** The audio sampling rate in Hz. */
    public final int sampleRate;

    /** The number of audio channels */
    public final int channelCount;

    /** The size of the frame. */
    public final int frameSize;

    /** Number of audio samples in the frame. */
    public final int sampleCount;

    /** The peak bitrate of audio samples. */
    public final int peakBitrate;

    /** Number of substreams. Dolby Atmos has 4 substreams. */
    public final int substreams;

    private MajorSyncInfo(
        @Nullable String mimeType,
        @MajorSyncInfo.StreamType int streamType,
        int channelCount,
        int sampleRate,
        int frameSize,
        int sampleCount,
        int peakBitrate,
        int substreams) {
      this.mimeType = mimeType;
      this.streamType = streamType;
      this.channelCount = channelCount;
      this.sampleRate = sampleRate;
      this.frameSize = frameSize;
      this.sampleCount = sampleCount;
      this.peakBitrate = peakBitrate;
      this.substreams = substreams;
    }
  }

  private final Ac3Reader ac3Reader;
  private final TrueHdSampleRechunker trueHdSampleRechunker;

  /*
  Dolby TrueHD (MLP) high-level bitstream description.pdf
   */

  /*
  mlp_sync
    check_nibble v(4)
    access_unit_length u(12)
    input_timing u(16)
 */
  private static final int MLP_SYNC_SIZE = 4;

  /*
  major_sync_info() {
    format_sync v(32) // 4 bytes from beginning
    format_info v(32) // + 4 = 8 bytes from beginning
    signature v(16) // + 2 = 10 bytes from beginning
    flags v(16) // + 2 = 12 bytes from beginning
    reserved v(16) // + 2 = 14 bytes from beginning
    variable_rate b(1)
    peak_data_rate u(15) // + 2 = 16 bytes from beginning
    substreams u(4)
    reserved v(2)
    extended_substream_info u(2) // + 1 = 17 bytes from beginning
    substream_info v(8) // + 1 = 18 bytes from beginning
    channel_meaning()
    major_sync_info_CRC u(16) // 2 bytes CRC
  }
  channel_meaning() {
    reserved v(6)
    2ch_control_enabled b(1)
    6ch_control_enabled b(1)
    8ch_control_enabled b(1)
    reserved v(1)
    drc_start_up_gain s(7)
    2ch_dialogue_norm u(6)
    2ch_mix_level u(6)
    6ch_dialogue_norm u(5)
    6ch_mix_level u(6)
    6ch_source_format v(5)
    8ch_dialogue_norm u(5)
    8ch_mix_level u(6)
    8ch_source_format v(6)
    reserved v(1)
    extra_channel_meaning_present b(1) // 8 bytes from beginning of channel_meaning
    if (extra_channel_meaning_present) {
      extra_channel_meaning_length u(4)
      extra_channel_meaning_data()
      padding pad(0…15)
    }
  }
  extra_channel_meaning_data() {
    if substream_info & 0x80 {
      16ch_channel_meaning()
      reserved v(((extra_channel_meaning_length+1)*16) - (16ch_channel_meaning_length_in_bits) - 4)
    }
    else {
      reserved v(((extra_channel_meaning_length+1)*16) - 4)
    }
 */
  // the minimum size in bytes for the major sync info bit stream element
  private static final int MAJOR_SYNC_INFO_SIZE_BASIC = 18 + 8 + 2;
  // MLP_SYNC_SIZE + MAJOR_SYNC_INFO_SIZE_BASIC is the minimum bytes to read to calc sync info size
  // since the header might cross over consume(), so combine all together into scratchBytes
  private static final int MAJOR_SYNC_SIZE_BASIC = MLP_SYNC_SIZE + MAJOR_SYNC_INFO_SIZE_BASIC;
  private static final int SYNC_MAJOR = 0xF8726F;
  public static final int SYNC_WORD_AC3 = 0x0B77;
  public static final int SYNC_DWORD_TRUEHD = 0xF8726FBA;
  // 32-bit word DWORD, is 4 bytes
  public static final int SYNC_DWORD_TRUEHD_LEN = 4;

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
      STATE_FINDING_MAJOR_SYNC,
      STATE_FINDING_ANY_SYNC,
      STATE_READING_HEADER,
      STATE_READING_SAMPLE
  })
  private @interface State {
  }

  private static final int STATE_FINDING_MAJOR_SYNC = 0;
  private static final int STATE_READING_HEADER = 1;
  private static final int STATE_READING_SAMPLE = 2;
  private static final int STATE_FINDING_ANY_SYNC = 3;

  private final ParsableByteArray headerBytes;
  private final ParsableByteArray scratchBytes;
  @Nullable
  private final String language;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  private @State int state;
  private int bytesRead;

  // for parsing the header.
  private @MonotonicNonNull Format format;
  @Nullable private MajorSyncInfo majorSyncInfo;
  private int sampleSize;
  private int substreams;

  // for reading the samples.
  private long timeUsFromPes;
  private long timeUsAtFrameStarted;
  private boolean firstSyncedFrame;

  /**
   * Constructs a new reader for TrueHD+AC3 elementary streams.
   *
   * @param language Track language.
   */
  public MlpReader(@Nullable String language) {
    headerBytes = new ParsableByteArray(new byte[MAJOR_SYNC_SIZE_BASIC], 0);
    scratchBytes = new ParsableByteArray(new byte[MLP_SYNC_SIZE + SYNC_DWORD_TRUEHD_LEN - 1], 0);
    state = STATE_FINDING_MAJOR_SYNC;
    timeUsAtFrameStarted = C.TIME_UNSET;
    timeUsFromPes = C.TIME_UNSET;
    this.language = language;
    ac3Reader = new Ac3Reader(language);
    trueHdSampleRechunker = new TrueHdSampleRechunker();
  }

  @Override
  public void seek() {
    majorSyncInfo = null;
    state = STATE_FINDING_MAJOR_SYNC;
    bytesRead = 0;
    headerBytes.reset(headerBytes.getData(), 0);
    scratchBytes.reset(scratchBytes.getData(), 0);
    trueHdSampleRechunker.reset();
    timeUsFromPes = C.TIME_UNSET;
    ac3Reader.seek();
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
    ac3Reader.createTracks(extractorOutput, idGenerator);
  }

  public void setTrackOutput(TrackOutput trackOutput) {
    this.output = trackOutput;
    ac3Reader.setTrackOutput(trackOutput);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUsFromPes = pesTimeUs;
    ac3Reader.packetStarted(pesTimeUs, flags);
  }

  @Override
  public void consume(ParsableByteArray pesBuffer) {
    //consumeNo++;
    Assertions.checkStateNotNull(output); // Asserts that createTracks has been called.
    while (pesBuffer.bytesLeft() > 0) {
      if (ac3Reader.state == Ac3Reader.STATE_READING_HEADER
          || ac3Reader.state == Ac3Reader.STATE_READING_SAMPLE) {
        ac3Reader.consume(pesBuffer);
        continue;
      }
      switch (state) {
        case STATE_FINDING_MAJOR_SYNC:
          if (skipToNextMajorSync(pesBuffer)) {
            state = STATE_READING_HEADER;
            firstSyncedFrame = true;
          }
          break;
        case STATE_READING_HEADER:
          int headerSize = readMajorSync(pesBuffer);
          if (headerSize > 0) {
            headerBytes.setPosition(0);
            assert headerBytes.bytesLeft() >= bytesRead;
            output.sampleData(headerBytes, headerSize);
            headerBytes.reset(headerBytes.getData(), 0);
            scratchBytes.reset(scratchBytes.getData(), 0);
            state = STATE_READING_SAMPLE;
            trueHdSampleRechunker.setFoundSyncframe(true);
          }
          break;
        case STATE_FINDING_ANY_SYNC:
          sampleSize = skipToNextAnySync(pesBuffer);
          if (sampleSize > 0) {
            int bytesToRead = min(headerBytes.bytesLeft(), sampleSize);
            output.sampleData(headerBytes, bytesToRead);
            bytesRead = bytesToRead;
            pesBuffer.skipBytes(pesBuffer.bytesLeft());
            if (headerBytes.bytesLeft() > 0) {
              scratchBytes.reset(headerBytes.getData(), headerBytes.limit());
              scratchBytes.setPosition(headerBytes.getPosition());
            } else {
              scratchBytes.reset(scratchBytes.getData(), 0);
            }
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          if (timeUsAtFrameStarted == C.TIME_UNSET) {
            timeUsAtFrameStarted = timeUsFromPes;
          }
          if (scratchBytes.bytesLeft() > 0) {
            int bytesToRead = min(scratchBytes.bytesLeft(), sampleSize - bytesRead);
            assert bytesToRead >= 0;
            output.sampleData(scratchBytes, bytesToRead);
            bytesRead += bytesToRead;
          }
          int bytesToRead = min(pesBuffer.bytesLeft(), sampleSize - bytesRead);
          assert bytesToRead >= 0;
          output.sampleData(pesBuffer, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == sampleSize) {
            // packetStarted method must be called before reading samples.
            checkState(timeUsAtFrameStarted != C.TIME_UNSET);
            int flag = firstSyncedFrame ? C.BUFFER_FLAG_KEY_FRAME : 0;
            firstSyncedFrame = false;
            trueHdSampleRechunker.sampleMetadata(
                output, timeUsAtFrameStarted, flag, sampleSize, 0, null);
            timeUsAtFrameStarted = C.TIME_UNSET;
            state = STATE_FINDING_ANY_SYNC;
          }
          break;
        default:
          break;
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    // Do nothing.
  }

  private void combineByteArrays(
      @NonNull ParsableByteArray source1,
      @NonNull ParsableByteArray source2,
      @Nullable ParsableByteArray target,
      int maxLength) {
    if (target == null) {
      target = new ParsableByteArray();
    } else {
      target.reset(target.getData(), 0);
    }
    if (maxLength == 0) {
      return;
    }
    int source1BytesLeft = source1.bytesLeft();
    if (source1BytesLeft == 0) {
      int position = source2.getPosition();
      int limit = source2.limit();
      if (maxLength > 0) {
        limit = Math.min(limit, position + maxLength);
      }
      target.reset(source2.getData(), limit);
      target.setPosition(position);
      return;
    }
    if (maxLength > 0 && source1BytesLeft >= maxLength) {
      int position = source1.getPosition();
      int limit = Math.min(source1.limit(), position + maxLength);
      target.reset(source1.getData(), limit);
      target.setPosition(position);
      return;
    }
    int source2BytesLeft = source2.bytesLeft();
    int length = source1BytesLeft + source2BytesLeft;
    if (maxLength > 0) {
      length = Math.min(length, maxLength);
    }
    int length1 = Math.min(length, source1BytesLeft);
    int length2 = length - length1;
    target.ensureCapacity(length);
    byte[] targetBuffer = target.getData();
    System.arraycopy(source1.getData(), source1.getPosition(), targetBuffer, 0, length1);
    System.arraycopy(source2.getData(), source2.getPosition(), targetBuffer, length1, length2);
    target.setLimit(length);
  }

  /**
   * Continues a read from the provided {@code source} into a given {@code target}. It's assumed
   * that the data should be written into {@code target} starting from an offset of zero.
   *
   * @param source       The source from which to read.
   * @param target       The target into which data is to be read.
   * @param targetLength The target length of the read.
   * @return Whether the target length was reached.
   */
  private boolean continueRead(
      ParsableByteArray source, ParsableByteArray target, int targetLength) {

    int bytesToRead = min(source.bytesLeft(), targetLength - bytesRead);
    if (bytesToRead <= 0) {
      return true;
    }
    target.ensureCapacity(targetLength);
    source.readBytes(target.getData(), bytesRead, bytesToRead);
    bytesRead += bytesToRead;
    headerBytes.setLimit(bytesRead);
    return bytesRead == targetLength;
  }

  // return bytes not enough: < 0, failed: 0, succeeded: > 0
  private int detectTrueHDSyncWord(@NonNull byte[] buffer, int offset, int bufferLimit) {
    assert offset <= bufferLimit && bufferLimit <= buffer.length;
    if (bufferLimit - offset >= MLP_SYNC_SIZE + SYNC_DWORD_TRUEHD_LEN) {
      // long variable store 8 bytes
      long prefixedSyncBytes = toUInt64BE(buffer, offset);
      return isTrueHDSyncDWord((int) prefixedSyncBytes) ? 1 : 0;
    }
    return -1;
  }

  /**
   * Locates the next Major SYNC value in the buffer, advancing the position to the SYNC word.
   * If SYNC was not located, the position is advanced to the limit.
   * scratchBytes hold the bytes to be used in next searching.
   *
   * @param pesBuffer The buffer whose position should be advanced.
   * @return Whether a syncword position was found.
   */
  private boolean skipToNextMajorSync(ParsableByteArray pesBuffer) {
    final int lengthRequired = MLP_SYNC_SIZE + SYNC_DWORD_TRUEHD_LEN;
    while (pesBuffer.bytesLeft() > 0) {
      if (scratchBytes.bytesLeft() + pesBuffer.bytesLeft() >= lengthRequired) {
        byte[] bytes;
        int start, limit;
        if (scratchBytes.bytesLeft() > 0) {
          bytes = new byte[lengthRequired];
          int length1 = scratchBytes.bytesLeft();
          System.arraycopy(scratchBytes.getData(), scratchBytes.getPosition(), bytes, 0, length1);
          int length2 = lengthRequired - length1;
          System.arraycopy(pesBuffer.getData(), pesBuffer.getPosition(), bytes, length1, length2);
          start = 0;
          limit = bytes.length;
        } else {
          bytes = pesBuffer.getData();
          start = pesBuffer.getPosition();
          limit = pesBuffer.limit();
        }
        if (detectTrueHDSyncWord(bytes, start, limit) > 0) {
          headerBytes.ensureCapacity(MAJOR_SYNC_SIZE_BASIC);
          headerBytes.reset(headerBytes.getData(), 0);
          byte[] dest = headerBytes.getData();
          System.arraycopy(bytes, start, dest, 0, lengthRequired);
          headerBytes.setLimit(lengthRequired);
          bytesRead = lengthRequired;
          int bytesToSkip = Math.min(scratchBytes.bytesLeft(), lengthRequired);
          scratchBytes.skipBytes(bytesToSkip);
          bytesToSkip = lengthRequired - bytesToSkip;
          pesBuffer.skipBytes(bytesToSkip);
          return true;
        } else {
          if (scratchBytes.bytesLeft() > 0) {
            scratchBytes.skipBytes(1);
          } else {
            pesBuffer.skipBytes(1);
          }
        }
      } else {
        if (scratchBytes.bytesLeft() == 0) {
          scratchBytes.reset(scratchBytes.getData(), 0);
        }
        int bytesToCopy = pesBuffer.bytesLeft();
        scratchBytes.ensureCapacity(scratchBytes.limit() + bytesToCopy);
        System.arraycopy(
            pesBuffer.getData(), pesBuffer.getPosition(),
            scratchBytes.getData(), scratchBytes.limit(), bytesToCopy);
        scratchBytes.setLimit(scratchBytes.limit() + bytesToCopy);
        assert scratchBytes.capacity() <= 13;
        pesBuffer.skipBytes(bytesToCopy);
      }
    }
    return false;
  }

  // returns sample size
  private int skipToNextAnySync(ParsableByteArray pesBuffer) {
    while (pesBuffer.bytesLeft() > 0) {
      ParsableByteArray array = headerBytes;
      // headerBytes holds all bytes available now
      combineByteArrays(scratchBytes, pesBuffer, array, -1);
      int resultMajor = detectTrueHDSyncWord(array.getData(), array.getPosition(), array.limit());
      int resultMinor = 0;
      if (resultMajor > 0) {
        pesBuffer.skipBytes(pesBuffer.bytesLeft());
        byte[] buffer = array.getData();
        int position = array.getPosition();
        return toUInt16((byte) (buffer[position] & 0xf), buffer[position + 1]) * 2;
      } else if (resultMajor == 0) {
        byte[] buffer = array.getData();
        int position = array.getPosition();
        resultMinor = detectMinorSync(buffer, position, array.limit(), true);
        if (resultMinor > 0) {
          scratchBytes.skipBytes(scratchBytes.bytesLeft());
          pesBuffer.skipBytes(pesBuffer.bytesLeft());
          return toUInt16((byte) (buffer[position] & 0xf), buffer[position + 1]) * 2;
        } else if (resultMinor == 0) {
          if (ac3Reader.state == Ac3Reader.STATE_FINDING_SYNC &&
              (isAc3SyncWord(buffer, position, array.limit())
                  || (ac3Reader.lastByteWas0B && buffer[position] == 0x77))) {
            ac3Reader.consume(pesBuffer);
          } else {
            if (scratchBytes.bytesLeft() > 0) {
              scratchBytes.skipBytes(1);
            } else {
              pesBuffer.skipBytes(1);
            }
          }
        }
      }
      if (resultMajor < 0 || resultMinor < 0) {
        scratchBytes.reset(array.getData(), array.limit());
        scratchBytes.setPosition(array.getPosition());
        pesBuffer.skipBytes(pesBuffer.bytesLeft());
      }
    }
    return -1;
  }

  // return bytes not enough: < 0, failed: 0, succeeded: > 0
  private int detectMinorSync(byte[] buf, int offset, int limit, boolean failOnAc3) {
    int p = offset;
    if (p + 1 >= limit) {
      return -1;
    }
    if (failOnAc3 && buf[p] == 0x0B && buf[p + 1] == 0x77) {
      return 0;
    }
    byte parityBits = 0;
    for (int i = -1; i < substreams; i++) {
      if (p + 2 < limit) {
        parityBits ^= buf[p++];
        parityBits ^= buf[p++];

        if ((i < 0 || (buf[p - 2] & 0x80) != 0)) {
          if (p + 2 < limit) {
            parityBits ^= buf[p++];
            parityBits ^= buf[p++];
          } else {
            return -1;
          }
        }
      } else {
        return -1;
      }
    }
    parityBits = (byte) (parityBits & 0xff);
    return (((parityBits >> 4) ^ parityBits) & 0xF) == 0xF ? 1 : 0;
  }

  private static final int CRC_TABLE_SIZE = 257;
  @Nullable private static final int[] crc2D = crcInit(0, 16, 0x002D, CRC_TABLE_SIZE);

  public static int swap16(int x) {
    return (((x << 8) & 0xff00) | ((x >> 8) & 0x00ff));
  }

  public static int swap32(int x) {
    return (swap16(x) << 16) | (swap16(x >> 16));
  }

  @Nullable public static int[] crcInit(int le, int bits, int poly, int size) {
    int[] m = new int[size];
    int i, j;
    int c;

    if (bits < 8 || bits > 32 || poly >= (1L << bits))
      return null;
    if (size != 257 && size != 1024)
      return null;

    for (i = 0; i < 256; i++) {
      if (le != 0) {
        for (c = i, j = 0; j < 8; j++) {
          c = (c >> 1) ^ (poly & (-(c & 1)));
        }
        m[i] = c;
      } else {
        for (c = i << 24, j = 0; j < 8; j++) {
          c = (c << 1) ^ ((poly << (32 - bits)) & ((c) >> 31));
        }
        m[i] = swap32(c);
      }
    }
    m[256] = 1;
    return m;
  }

  public static int crc(@Nullable int[] m, int crc, byte[] bytes, int start, int length) {
    if (m == null || m.length < 255) {
      return -1;
    }
    int end = start + length;
    while (start < end) {
      crc = m[(crc ^ bytes[start++]) & 0xff] ^ (crc >> 8);
    }
    return crc;
  }

  int checkSum16(byte[] buf, int start, int length) {
    int crc = crc(crc2D, 0, buf, start, length - 2);
    crc ^= toUInt16LE(buf, start + length - 2);
    return crc;
  }

  private int getMajorSyncInfoSize(ParsableByteArray pesBuffer) {
    if (!continueRead(pesBuffer, headerBytes, MAJOR_SYNC_SIZE_BASIC)) {
      return -1;
    }

    boolean hasExtension;
    int infoPosition = MLP_SYNC_SIZE;
    int size = MAJOR_SYNC_INFO_SIZE_BASIC;
    byte[] dataBuffer = headerBytes.getData();
    // bytes before channel_meaning is 18, bytes of channel meaning is 8
    final int indexOfExtraChannelMeaningPresent = 25;
    hasExtension = (dataBuffer[infoPosition + indexOfExtraChannelMeaningPresent] & 1) > 0;
    if (hasExtension) {
      // extra_channel_meaning_length is follows indexOfExtraChannelMeaningPresent, 4 bits
      int extensions = (dataBuffer[infoPosition + indexOfExtraChannelMeaningPresent + 1]) >> 4;
      size += 2 + extensions * 2;
    }
    return size;
  }

  /**
   * Parses the sample header.
   * Returns header size.
   */
  @RequiresNonNull("output")
  private int readMajorSync(ParsableByteArray pesBuffer) {
    if (!continueRead(pesBuffer, headerBytes, MAJOR_SYNC_SIZE_BASIC)) {
      return -1;
    }
    headerBytes.setLimit(MAJOR_SYNC_SIZE_BASIC);
    majorSyncInfo = parseMlpMajorSyncInfo(pesBuffer);
    if (majorSyncInfo == null) {
      return -1;
    }
    if (format == null
        || majorSyncInfo.channelCount != format.channelCount
        || majorSyncInfo.sampleRate != format.sampleRate
        || !Util.areEqual(majorSyncInfo.mimeType, format.sampleMimeType)) {
      Format.Builder formatBuilder =
          new Format.Builder()
              .setId(formatId)
              .setSampleMimeType(majorSyncInfo.mimeType)
              .setChannelCount(majorSyncInfo.channelCount)
              .setSampleRate(majorSyncInfo.sampleRate)
              .setLanguage(language)
              .setPeakBitrate(majorSyncInfo.peakBitrate);
      if (MimeTypes.AUDIO_TRUEHD.equals(majorSyncInfo.mimeType)) {
        formatBuilder.setAverageBitrate(majorSyncInfo.peakBitrate);
      }
      format = formatBuilder.build();
      output.format(format);
    }
    sampleSize = majorSyncInfo.frameSize;
    substreams = majorSyncInfo.substreams;
    return headerBytes.limit();
  }

  /*
Value Sampling Rate
  0000 48 kHz
  0001 96 kHz
  0010 192 kHz
  1000 44.1 kHz
  1001 88.2 kHz
  1010 176.4 kHz
  Others reserved
 */
  public static int getMlpSampleRate(int rateBits) {
    if (rateBits == 0xF) {
      return 0;
    }
    return (((rateBits & 8) != 0) ? 44100 : 48000) << (rateBits & 7);
  }

  public final static int[] TRUEHD_CHANNEL_COUNT = new int[]{
      // LR C LFE LRs LRvh LRc LRrs Cs Ts LRsd LRw Cvh LFE2
      2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 1, 1
  };

  public static final int
      AV_CHAN_FRONT_LEFT = 0,
      AV_CHAN_FRONT_RIGHT = 1,
      AV_CHAN_FRONT_CENTER = 2,
      AV_CHAN_LOW_FREQUENCY = 3,
      AV_CHAN_BACK_LEFT = 4,
      AV_CHAN_BACK_RIGHT = 5,
      AV_CHAN_FRONT_LEFT_OF_CENTER = 6,
      AV_CHAN_FRONT_RIGHT_OF_CENTER = 7,
      AV_CHAN_BACK_CENTER = 8,
      AV_CHAN_SIDE_LEFT = 9,
      AV_CHAN_SIDE_RIGHT = 10,
      AV_CHAN_TOP_CENTER = 11,
      AV_CHAN_TOP_FRONT_LEFT = 12,
      AV_CHAN_TOP_FRONT_CENTER = 13,
      AV_CHAN_TOP_FRONT_RIGHT = 14,
      AV_CHAN_TOP_BACK_LEFT = 15,
      AV_CHAN_TOP_BACK_CENTER = 16,
      AV_CHAN_TOP_BACK_RIGHT = 17,
  /** Stereo downmix. */
  AV_CHAN_STEREO_LEFT = 29,
  /** See above. */
  AV_CHAN_STEREO_RIGHT = 30,
      AV_CHAN_WIDE_LEFT = 31,
      AV_CHAN_WIDE_RIGHT = 32,
      AV_CHAN_SURROUND_DIRECT_LEFT = 33,
      AV_CHAN_SURROUND_DIRECT_RIGHT = 34,
      AV_CHAN_LOW_FREQUENCY_2 = 35,
      AV_CHAN_TOP_SIDE_LEFT = 36,
      AV_CHAN_TOP_SIDE_RIGHT = 37,
      AV_CHAN_BOTTOM_FRONT_CENTER = 38,
      AV_CHAN_BOTTOM_FRONT_LEFT = 39,
      AV_CHAN_BOTTOM_FRONT_RIGHT = 40,
      AV_CHAN_SIDE_SURROUND_LEFT = 41,     ///<  +90 degrees, Lss, SiL
  AV_CHAN_SIDE_SURROUND_RIGHT = 42,    ///<  -90 degrees, Rss, SiR
  AV_CHAN_TOP_SURROUND_LEFT = 43,      ///< +110 degrees, Lvs, TpLS
  AV_CHAN_TOP_SURROUND_RIGHT = 44,     ///< -110 degrees, Rvs, TpRS

  /** Channel is empty can be safely skipped. */
  AV_CHAN_UNUSED = 0x200,

  /** Channel contains data, but its position is unknown. */
  AV_CHAN_UNKNOWN = 0x300,

  /**
   * Range of channels between AV_CHAN_AMBISONIC_BASE and
   * AV_CHAN_AMBISONIC_END represent Ambisonic components using the ACN system.
   *
   * Given a channel id `<i>` between AV_CHAN_AMBISONIC_BASE and
   * AV_CHAN_AMBISONIC_END (inclusive), the ACN index of the channel `<n>` is
   * `<n> = <i> - AV_CHAN_AMBISONIC_BASE`.
   *
   * @note these values are only used for AV_CHANNEL_ORDER_CUSTOM channel
   * orderings, the AV_CHANNEL_ORDER_AMBISONIC ordering orders the channels
   * implicitly by their position in the stream.
   */
  AV_CHAN_AMBISONIC_BASE = 0x400,
  // leave space for 1024 ids, which correspond to maximum order-32 harmonics,
  // which should be enough for the foreseeable use cases
  AV_CHAN_AMBISONIC_END  = 0x7ff;

  public static final long AV_CH_FRONT_LEFT             = 1L << AV_CHAN_FRONT_LEFT           ;
  public static final long AV_CH_FRONT_RIGHT            = 1L << AV_CHAN_FRONT_RIGHT          ;
  public static final long AV_CH_FRONT_CENTER           = 1L << AV_CHAN_FRONT_CENTER         ;
  public static final long AV_CH_LOW_FREQUENCY          = 1L << AV_CHAN_LOW_FREQUENCY        ;
  public static final long AV_CH_BACK_LEFT              = 1L << AV_CHAN_BACK_LEFT            ;
  public static final long AV_CH_BACK_RIGHT             = 1L << AV_CHAN_BACK_RIGHT           ;
  public static final long AV_CH_FRONT_LEFT_OF_CENTER   = 1L << AV_CHAN_FRONT_LEFT_OF_CENTER ;
  public static final long AV_CH_FRONT_RIGHT_OF_CENTER  = 1L << AV_CHAN_FRONT_RIGHT_OF_CENTER;
  public static final long AV_CH_BACK_CENTER            = 1L << AV_CHAN_BACK_CENTER          ;
  public static final long AV_CH_SIDE_LEFT              = 1L << AV_CHAN_SIDE_LEFT            ;
  public static final long AV_CH_SIDE_RIGHT             = 1L << AV_CHAN_SIDE_RIGHT           ;
  public static final long AV_CH_TOP_CENTER             = 1L << AV_CHAN_TOP_CENTER           ;
  public static final long AV_CH_TOP_FRONT_LEFT         = 1L << AV_CHAN_TOP_FRONT_LEFT       ;
  public static final long AV_CH_TOP_FRONT_CENTER       = 1L << AV_CHAN_TOP_FRONT_CENTER     ;
  public static final long AV_CH_TOP_FRONT_RIGHT        = 1L << AV_CHAN_TOP_FRONT_RIGHT      ;
  public static final long AV_CH_TOP_BACK_LEFT          = 1L << AV_CHAN_TOP_BACK_LEFT        ;
  public static final long AV_CH_TOP_BACK_CENTER        = 1L << AV_CHAN_TOP_BACK_CENTER      ;
  public static final long AV_CH_TOP_BACK_RIGHT         = 1L << AV_CHAN_TOP_BACK_RIGHT       ;
  public static final long AV_CH_STEREO_LEFT            = 1L << AV_CHAN_STEREO_LEFT          ;
  public static final long AV_CH_STEREO_RIGHT           = 1L << AV_CHAN_STEREO_RIGHT         ;
  public static final long AV_CH_WIDE_LEFT              = 1L << AV_CHAN_WIDE_LEFT            ;
  public static final long AV_CH_WIDE_RIGHT             = 1L << AV_CHAN_WIDE_RIGHT           ;
  public static final long AV_CH_SURROUND_DIRECT_LEFT   = 1L << AV_CHAN_SURROUND_DIRECT_LEFT ;
  public static final long AV_CH_SURROUND_DIRECT_RIGHT  = 1L << AV_CHAN_SURROUND_DIRECT_RIGHT;
  public static final long AV_CH_LOW_FREQUENCY_2        = 1L << AV_CHAN_LOW_FREQUENCY_2      ;
  public static final long AV_CH_TOP_SIDE_LEFT          = 1L << AV_CHAN_TOP_SIDE_LEFT        ;
  public static final long AV_CH_TOP_SIDE_RIGHT         = 1L << AV_CHAN_TOP_SIDE_RIGHT       ;
  public static final long AV_CH_BOTTOM_FRONT_CENTER    = 1L << AV_CHAN_BOTTOM_FRONT_CENTER  ;
  public static final long AV_CH_BOTTOM_FRONT_LEFT      = 1L << AV_CHAN_BOTTOM_FRONT_LEFT    ;
  public static final long AV_CH_BOTTOM_FRONT_RIGHT     = 1L << AV_CHAN_BOTTOM_FRONT_RIGHT   ;
  public static final long AV_CH_SIDE_SURROUND_LEFT     = 1L << AV_CHAN_SIDE_SURROUND_LEFT   ;
  public static final long AV_CH_SIDE_SURROUND_RIGHT    = 1L << AV_CHAN_SIDE_SURROUND_RIGHT  ;
  public static final long AV_CH_TOP_SURROUND_LEFT      = 1L << AV_CHAN_TOP_SURROUND_LEFT    ;
  public static final long AV_CH_TOP_SURROUND_RIGHT     = 1L << AV_CHAN_TOP_SURROUND_RIGHT   ;
  public final static long[] TRUEHD_LAYOUT = new long[]{
      AV_CH_FRONT_LEFT|AV_CH_FRONT_RIGHT,                     // LR
      AV_CH_FRONT_CENTER,                                     // C
      AV_CH_LOW_FREQUENCY,                                    // LFE
      AV_CH_SIDE_LEFT|AV_CH_SIDE_RIGHT,                       // LRs
      AV_CH_TOP_FRONT_LEFT|AV_CH_TOP_FRONT_RIGHT,             // LRvh
      AV_CH_FRONT_LEFT_OF_CENTER|AV_CH_FRONT_RIGHT_OF_CENTER, // LRc
      AV_CH_BACK_LEFT|AV_CH_BACK_RIGHT,                       // LRrs
      AV_CH_BACK_CENTER,                                      // Cs
      AV_CH_TOP_CENTER,                                       // Ts
      AV_CH_SURROUND_DIRECT_LEFT|AV_CH_SURROUND_DIRECT_RIGHT, // LRsd
      AV_CH_WIDE_LEFT|AV_CH_WIDE_RIGHT,                       // LRw
      AV_CH_TOP_FRONT_CENTER,                                 // Cvh
      AV_CH_LOW_FREQUENCY_2,                                  // LFE2
  };

  public static int getTruehdChannelNum(int channelMap) {
    int channelCount = 0;
    for (int i = 0; i < 13; i++) {
      channelCount += TRUEHD_CHANNEL_COUNT[i] * ((channelMap >> i) & 1);
    }
    return channelCount;
  }

  public static long getTruehdLayout(int channelMap) {
    long layout = 0;
    for (int i = 0; i < 13; i++) {
      layout |= TRUEHD_LAYOUT[i] * ((channelMap >> i) & 1);
    }
    return layout;
  }

  public static boolean isAc3SyncWord(@NonNull byte[] bytes, int offset, int limit) {
    return limit - offset >= 2
        && bytes[offset] == (byte) ((SYNC_WORD_AC3 >> 8) & 0xff)
        && bytes[offset + 1] == (byte) (SYNC_WORD_AC3 & 0xff);
  }

  public static boolean isTrueHDSyncDWord(int dWord) {
    return dWord == SYNC_DWORD_TRUEHD;
  }

  // for TrueHD only
  @Nullable
  private MajorSyncInfo parseMlpMajorSyncInfo(ParsableByteArray pesBuffer) {
    // this headerSize not includes substream_directory, nor MLP_SYNC_SIZE, only major info size
    int majorSyncInfoSize = getMajorSyncInfoSize(pesBuffer);
    if (majorSyncInfoSize < 0) {
      return null;
    }
    int headerSize = majorSyncInfoSize + MLP_SYNC_SIZE;
    if (!continueRead(pesBuffer, headerBytes, headerSize)) {
      return null;
    }

    byte[] buffer = headerBytes.getData();
    int checksumCalculated = checkSum16(buffer, MLP_SYNC_SIZE, majorSyncInfoSize - 2);
    int checksum = toUInt16LE(buffer, headerSize - 2);
    if (checksumCalculated != checksum) {
      return null;
    }
    ParsableBitArray bitArray = new ParsableBitArray(headerBytes.getData());
    // mlp_sync
    bitArray.skipBits(32);
    if (bitArray.readBits(24) != SYNC_MAJOR) {
      return null;
    }

    // 0xba (TRUEHD) or 0xbb (MLP)
    int streamType = bitArray.readBits(8);
    if (streamType != MajorSyncInfo.STREAM_TYPE_TRUEHD) {
      return null;
    }

    // format_info(32-bit)
    int samplingRateValue = bitArray.readBits(4);
    int sampleRate = getMlpSampleRate(samplingRateValue);
    // 6ch_multichannel_type(1-bit)
    // 8ch_multichannel_type(1)
    // reserved(2)
    // 2ch_presentation_channel_modifier (2)
    // 6ch_presentation_channel_modifier (2)
    bitArray.skipBits(1 + 1 + 2 + 2 + 2);
    // 6ch_presentation_channel_assignment(5)
    int channelArrangement = bitArray.readBits(5);
    int channelsThdStream1 = getTruehdChannelNum(channelArrangement);
    // 8ch_presentation_channel_modifier(2)
    bitArray.skipBits(2);
    // 8ch_presentation_channel_assignment(13)
    channelArrangement = bitArray.readBits(13);
    int channelsThdStream2 = getTruehdChannelNum(channelArrangement);
    int accessUnitSize = 40 << (samplingRateValue & 7);
    // signature(16)(MAJOR_SYNC_INFO_SIGNATURE = 0xB752), flags(16), reserved(16)
    bitArray.skipBits(16 + 16 + 16);
    // variable_rate(1)
    bitArray.skipBit();
    // peak_data_rate(15)
    int peakBitrate = (bitArray.readBits(15) * sampleRate + 8) >> 4;
    // substreams(4)
    int substreamsNum = bitArray.readBits(4);
    // reserved(2)
    bitArray.skipBits(2);
    // extended_substream_info(2)
    bitArray.skipBits(2);
    // substream_info(8)
    int substreamInfo = bitArray.readBits(8);
    boolean sixteenChannels = (substreamInfo & 0x80) != 0;
    // (headerSize - 18) bytes ignored

    // 24-bit frame size combined by 4-bit the lower nibble of 1st byte and 8-bit of 2nd byte
    int frameSize = toUInt16((byte) (buffer[0] & 0xf), buffer[1]) * 2;
    return new MajorSyncInfo(
        MimeTypes.AUDIO_TRUEHD,
        streamType,
        sixteenChannels ? 16 : channelsThdStream2 > 0 ? channelsThdStream2 : channelsThdStream1,
        sampleRate,
        frameSize,
        accessUnitSize,
        peakBitrate,
        substreamsNum);
  }

  public static int toUInt16(byte byteHigh, byte byteLow) {
    return ((byteHigh & 0xff) << 8) | (byteLow & 0xff);
  }

  public static int toUInt16LE(byte[] bytes, int start) {
    checkArgument(start + 2 <= bytes.length);
    return (bytes[start + 1] & 0xff) << 8 | (bytes[start] & 0xff);
  }

  public static long toUInt64BE(byte[] bytes, int start) {
    checkArgument(start + 8 <= bytes.length);
    long l = 0;
    for (int i = start; i < start + 8; i++) {
      l = (l << 8) | (bytes[i] & 0xff);
    }
    return l;
  }
}
