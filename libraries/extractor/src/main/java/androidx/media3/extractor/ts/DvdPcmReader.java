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

/** Parses and extracts samples from an DVD PCM elementary stream. */
@UnstableApi
public final class DvdPcmReader
    implements ElementaryStreamReader, ElementaryStreamReaderStub.IFormatOutput {

  @Override
  public void outputFormat(Format format) {
    this.format = format.buildUpon().setId(formatId).build();
    this.output.format(format);
  }

  private static final int STATE_READING_HEADER = 0;
  private static final int STATE_READING_SAMPLE = 1;

  private static final int HEADER_SIZE = 3;

  @Nullable private final String language;
  private final ParsableByteArray headerBuffer;
  private final ParsableByteArray leftOverSamples;

  // Track output info.
  private @MonotonicNonNull TrackOutput output;
  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull Format format;

  // Header data.
  private int bitsPerSample;
  private int channelCount;
  private int sampleRateHz;
  private int blockSize, groupsPerBlock, samplesPerBlock;

  // State info.
  private int state;
  private long timeUs;

  /**
   * @param language Track language.
   */
  public DvdPcmReader(@Nullable String language) {
    this.language = language;
    headerBuffer = new ParsableByteArray(new byte[HEADER_SIZE], 0);
    leftOverSamples = new ParsableByteArray();
    timeUs = 0;
  }

  @Override
  public void seek() {
    headerBuffer.reset(0);
    leftOverSamples.reset(0);
    state = STATE_READING_HEADER;
    timeUs = 0;
  }

  private boolean trackCreated;

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    if (trackCreated) {
      return;
    }
    trackCreated = true;
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
    formatId = idGenerator.getFormatId();
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs > 0) {
      timeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    Assertions.checkStateNotNull(output); // Asserts that createTracks has been called.
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
                    .setPcmEncoding(
                        Util.getPcmEncoding(
                            bitsPerSample == 20 ? 24 : bitsPerSample,
                            ByteOrder.BIG_ENDIAN))
                    .setSampleRate(sampleRateHz)
                    .build();
            if (!format.equals(this.format)) {
              this.format = format;
              output.format(format);
            }
            headerBuffer.reset(0);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          processSamples(data);
          state = STATE_READING_HEADER;
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

  private void appendBytes(ParsableByteArray source, ParsableByteArray target, int len) {
    target.ensureCapacity(target.limit() + len);
    System.arraycopy(
        source.getData(), source.getPosition(),
        target.getData(), target.limit(), len);
    source.skipBytes(len);
    target.setLimit(target.limit() + len);
  }

  // return total duration
  private long outputSamples(ParsableByteArray data, int blocks) {
    assert data.bytesLeft() >= blocks * blockSize;
    int bytesToRead = blocks * blockSize;
    output.sampleData(data, bytesToRead);
    return blocks * samplesPerBlock * C.MICROS_PER_SECOND / sampleRateHz;
  }

  private void processSamples(ParsableByteArray data) {
    int blocksOutputted = 0;
    long durationAddUp = 0;
    int leftOverCount = leftOverSamples.bytesLeft();
    if (leftOverCount > 0) {
      int missingBytes = blockSize - leftOverCount;
      int availableBytes = data.bytesLeft();
      if (availableBytes >= missingBytes) {
        appendBytes(data, leftOverSamples, missingBytes);
        durationAddUp += outputSamples(leftOverSamples, 1);
        leftOverSamples.reset(0);
        blocksOutputted++;
      } else {
        /* new packet still doesn't have enough samples */
        appendBytes(data, leftOverSamples, availableBytes);
        return;
      }
    }
    int blocks = data.bytesLeft() / blockSize;
    if (blocks > 0) {
      durationAddUp += outputSamples(data, blocks);
      blocksOutputted += blocks;
    }
    if (blocksOutputted > 0 && durationAddUp > 0) {
      output.sampleMetadata(
          timeUs, C.BUFFER_FLAG_KEY_FRAME, blocksOutputted * blockSize, 0, null);
      timeUs += durationAddUp;
    }
    int bytesLeft = data.bytesLeft();
    if (bytesLeft > 0) {
      appendBytes(data, leftOverSamples, bytesLeft);
    }
  }

  private boolean readValidHeader(ParsableByteArray data, ParsableByteArray header) {
    while (data.bytesLeft() > 0) {
      int bytesToRead = Math.min(data.bytesLeft(), HEADER_SIZE - header.limit());
      data.readBytes(header.getData(), header.limit(), bytesToRead);
      header.setLimit(header.limit() + bytesToRead);
      if (header.limit() >= HEADER_SIZE) {
        if (validateHeader(header)) {
          return true;
        } else {
          System.arraycopy(header.getData(), 1, header.getData(), 0, HEADER_SIZE - 1);
          header.setLimit(HEADER_SIZE);
        }
      }
    }
    return false;
  }

  private static final int[] frequencies = { 48000, 96000, 44100, 32000 };

  /*
   * header[0] emphasis (1), muse(1), reserved(1), frame number(5)
   * header[1] quant (2), freq(2), reserved(1), channels(3)
   * header[2] dynamic range control (0x80 = off)
   */
  private boolean validateHeader(ParsableByteArray a) {
    ParsableBitArray bits = new ParsableBitArray(a.getData());
    bits.skipBits(8);
    bitsPerSample = 16 + bits.readBits(2) * 4;
    sampleRateHz = frequencies[bits.readBits(2)];
    bits.skipBit();
    channelCount = 1 + (bits.readBits(3));
    if (channelCount == -1 || sampleRateHz == -1 || bitsPerSample == -1) {
      return false;
    }
    if (bitsPerSample == 16) {
      samplesPerBlock = 1;
      blockSize = channelCount * 2;
    } else {
      switch (channelCount) {
        case 1:
        case 2:
        case 4:
          /* one group has all the samples needed */
          blockSize = 4 * bitsPerSample / 8;
          samplesPerBlock = 4 / channelCount;
          groupsPerBlock = 1;
          break;
        case 8:
          /* two groups have all the samples needed */
          blockSize = 8 * bitsPerSample / 8;
          samplesPerBlock = 1;
          groupsPerBlock = 2;
          break;
        default:
          /* need channels groups */
          blockSize = 4 * channelCount * bitsPerSample / 8;
          samplesPerBlock = 4;
          groupsPerBlock = channelCount;
          break;
      }
    }
    return true;
  }
}
