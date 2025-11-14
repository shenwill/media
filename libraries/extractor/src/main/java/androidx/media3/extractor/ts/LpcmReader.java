/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.nio.ByteOrder;

/** Parses and extracts samples from an AAC/LATM elementary stream. */
@UnstableApi
public final class LpcmReader implements ElementaryStreamReader {

  private static final int STATE_READING_HEADER = 0;
  private static final int STATE_READING_SAMPLE = 1;

  @Nullable private final String language;
  private final ParsableByteArray headerBuffer;

  // Track output info.
  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull Format format;

  // Header data.
  private int bitsPerSample;
  private int channelCount;
  private long frameDurationUs;
  private int sampleRateHz;

  // State info.
  private int state;
  private int bytesRead;
  private int frameSize;
  private long timeUs;

  /**
   * @param language Track language.
   */
  public LpcmReader(@Nullable String language) {
    this.language = language;
    headerBuffer = new ParsableByteArray(new byte[4], 0);
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    bytesRead = 0;
    state = STATE_READING_HEADER;
    timeUs = C.TIME_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
    formatId = idGenerator.getFormatId();
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    Assertions.checkStateNotNull(output); // Asserts that createTracks has been called.
    int bytesToRead;
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_READING_HEADER:
          if (readValidHeader(data, headerBuffer)) {
            Format format =
                new Format.Builder()
                    .setId(formatId)
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setChannelCount(channelCount)
                    .setLanguage(language)
                    .setPcmEncoding(Util.getPcmEncoding(
                        bitsPerSample == 20 ? 24 : bitsPerSample, ByteOrder.BIG_ENDIAN))
                    .setSampleRate(sampleRateHz)
                    .build();
            if (!format.equals(this.format)) {
              this.format = format;
              output.format(format);
            }
            headerBuffer.setLimit(0);
            bytesRead = 0;
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          bytesToRead = min(data.bytesLeft(), frameSize - bytesRead);
          output.sampleData(data, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == frameSize) {
            output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
            timeUs += frameDurationUs;
            state = STATE_READING_HEADER;
          }
          break;
        default:
          throw new IllegalStateException();
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    // Do nothing.
  }

  private boolean readValidHeader(ParsableByteArray data, ParsableByteArray header) {
    while (data.bytesLeft() > 0) {
      int bytesToRead = Math.min(data.bytesLeft(), 4 - header.limit());
      data.readBytes(header.getData(), header.limit(), bytesToRead);
      header.setLimit(header.limit() + bytesToRead);
      if (header.limit() >= 4) {
        if (validateHeader(header)) {
          return true;
        } else {
          System.arraycopy(header.getData(), 1, header.getData(), 0, 3);
          header.setLimit(3);
        }
      }
    }
    return false;
  }

  /*
  size in bytes = 16 bits
  channel assignment = 4 bits
  sampling frequency = 4 bits
  bits per sample = 2 bits
  start flag = 1 bit
  reserved = 5 bits

  channel assignment
  1 = mono
  3 = stereo
  4 = 3/0
  5 = 2/1
  6 = 3/1
  7 = 2/2
  8 = 3/2
  9 = 3/2+lfe
  10 = 3/4
  11 = 3/4+lfe

  sampling frequency
  1 = 48 kHz
  4 = 96 kHz
  5 = 192 kHz

  bits per sample
  1 = 16
  2 = 20
  3 = 24
   */
  private boolean validateHeader(ParsableByteArray a) {
    ParsableBitArray b = new ParsableBitArray(a.getData());
    frameSize = b.readBits(16);
    int channelAssignment = b.readBits(4);
    int samplingFrequency = b.readBits(4);
    int bitDepth = b.readBits(2);
    boolean startFlag = b.readBit();
    channelCount = getChannelCount(channelAssignment);
    sampleRateHz = getSampleRate(samplingFrequency);
    bitsPerSample = getBitDepth(bitDepth);
    if (channelCount == -1 || sampleRateHz == -1 || bitsPerSample == -1) {
      return false;
    }
    int bytesPerSampleAllChannels = Math.round(bitsPerSample / 8.f) * channelCount;
    if (frameSize % bytesPerSampleAllChannels != 0) {
      return false;
    }
    frameDurationUs = C.MICROS_PER_SECOND * (frameSize / bytesPerSampleAllChannels) / sampleRateHz;
    return frameDurationUs <= 500_000;
  }

  private int getChannelCount(int channelAssignment) {
    switch (channelAssignment) {
      case 1:
        return 1;
      case 3:
        return 2;
      case 4:
      case 5:
        return 3;
      case 6:
      case 7:
        return 4;
      case 8:
        return 5;
      case 9:
        return 6;
      case 10:
        return 7;
      case 11:
        return 8;
      default:
        return -1;
    }
  }

  private int getSampleRate(int samplingFrequency) {
    switch (samplingFrequency) {
      case 1:
        return 48_000;
      case 4:
        return 96_000;
      case 5:
        return 192_000;
      default:
        return -1;
    }
  }

  private int getBitDepth(int bitsPerSample) {
    switch (bitsPerSample) {
      case 1:
        return 16;
      case 2:
        return 20;
      case 3:
        return 24;
      default:
        return -1;
    }
  }
}
