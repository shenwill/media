/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.extractor.ape;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorInput;

import com.google.common.base.Charsets;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.io.IOException;
import java.util.Arrays;

public final class ApeHeaderReader {

  public final static int MAC_FORMAT_FLAG_8_BIT = 1;
  public final static int MAC_FORMAT_FLAG_CRC = 2;
  public final static int MAC_FORMAT_FLAG_HAS_PEAK_LEVEL = 4;
  public final static int MAC_FORMAT_FLAG_24_BIT = 8;
  public final static int MAC_FORMAT_FLAG_HAS_SEEK_ELEMENTS = 16;
  public final static int MAC_FORMAT_FLAG_CREATE_WAV_HEADER = 32;

  private static final byte[] apeSignature = "MAC ".getBytes(Charsets.US_ASCII);

  public static boolean checkFileType(final ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    byte[] bytes = new byte[4];
    input.peekFully(bytes, 0, bytes.length);
    return Arrays.equals(bytes, apeSignature);
  }

  public static ApeInfo read(final ExtractorInput input) throws IOException {
    Assertions.checkState(input.getPosition() == 0);
    if (!checkFileType(input)) {
      // Should only happen if the media wasn't sniffed.
      throw ParserException.createForMalformedContainer(
          "Unsupported or unrecognized APE file.", /* cause= */ null);
    }

    int version = peekVersion(input);
    input.resetPeekPosition();
    ApeInfo info = version >= 3980 ? readV3980(input) : readV0000(input);

    info.totalSamples = info.totalFrames == 0 ? 0 :
        (long) (info.totalFrames - 1) * info.blocksPerFrame + info.finalFrameBlocks;
    info.durationUs = info.totalSamples * C.MICROS_PER_SECOND / info.sampleRate;

    return info;
  }

  private static int peekVersion(final ExtractorInput input) throws IOException {
    byte[] bytes = new byte[2];
    input.peekFully(bytes, 0, bytes.length);
    ParsableByteArray scratch = new ParsableByteArray(bytes);
    return scratch.readLittleEndianUnsignedShort();
  }

  private static ApeInfo readV3980(final ExtractorInput input) throws IOException {

    ApeInfo info = new ApeInfo();

    ApeDescriptor descriptor = ApeDescriptor.read(input);
    if (descriptor.descriptorLength > ApeDescriptor.DESCRIPTOR_LENGTH) {
      input.advancePeekPosition(
          (int) (descriptor.descriptorLength - ApeDescriptor.DESCRIPTOR_LENGTH));
    }

    final ApeHeaderV3980 header = ApeHeaderV3980.read(input);
    if (descriptor.headerLength > ApeHeaderV3980.HEADER_LENGTH) {
      input.advancePeekPosition(
          (int) (descriptor.headerLength - ApeHeaderV3980.HEADER_LENGTH));
    }
    fillFileInfo(info, descriptor, header);

    return info;
  }

  private static ApeInfo readV0000(final ExtractorInput input) throws IOException {

    ApeInfo info = new ApeInfo();

    ApeHeaderV0000 header = ApeHeaderV0000.read(input);
    fillFileInfo(info, header);

    ParsableByteArray scratch = new ParsableByteArray(4);

    info.peakLevel = -1;
    if ((header.formatFlags & MAC_FORMAT_FLAG_HAS_PEAK_LEVEL) != 0) {
      scratch.setPosition(0);
      input.peekFully(scratch.getData(), 0, 4);
      info.peakLevel = (int) scratch.readLittleEndianUnsignedInt();
      info.headerLength += 4;
    }

    int seekTableElementCount;
    if ((header.formatFlags & MAC_FORMAT_FLAG_HAS_SEEK_ELEMENTS) != 0) {
      scratch.setPosition(0);
      input.peekFully(scratch.getData(), 0, 4);
      seekTableElementCount = (int) scratch.readLittleEndianUnsignedInt();
      info.headerLength += 4;
    } else {
      seekTableElementCount = (int) header.totalFrames;
    }
    info.seekTableLength = seekTableElementCount * 4L;

    if ((header.formatFlags & MAC_FORMAT_FLAG_CREATE_WAV_HEADER) == 0) {
      input.advancePeekPosition((int) info.wavHeaderLength);
    }

    return info;
  }

  private static void fillFileInfo(
      final ApeInfo info, final ApeDescriptor descriptor, final ApeHeaderV3980 header) {

    info.fileVersion = descriptor.fileVersion;
    info.descriptorLength = descriptor.descriptorLength;
    info.headerLength = descriptor.headerLength;
    info.wavHeaderLength = descriptor.wavHeaderLength;
    info.wavTailLength = descriptor.wavTailLength;
    info.seekTableLength = descriptor.seekTableLength;

    info.compressionType = header.compressionLevel;
    info.formatFlags = header.formatFlags;
    info.totalFrames = (int) header.totalFrames;
    info.finalFrameBlocks = (int) header.finalFrameBlocks;
    info.blocksPerFrame = (int) header.blocksPerFrame;
    info.channels = header.channels;
    info.sampleRate = header.sampleRate;
    info.bitsPerSample = header.bitsPerSample;
    info.peakLevel = -1;
  }

  private static void fillFileInfo(ApeInfo info, ApeHeaderV0000 header) {

    info.descriptorLength = 0;
    info.headerLength = ApeHeaderV0000.HEADER_LENGTH;

    info.fileVersion = header.fileVersion;
    info.compressionType = header.compressionType;
    info.formatFlags = header.formatFlags;
    info.totalFrames = (int) header.totalFrames;
    info.finalFrameBlocks = (int) header.finalFrameBlocks;
    info.blocksPerFrame = header.fileVersion >= 3950 ? 73728 * 4
        : ((header.fileVersion >= 3900
        || (header.fileVersion >= 3800 && header.compressionType >= 4000)) ? 73728 : 9216);
    info.channels = header.channels;
    info.sampleRate = header.sampleRate;
    info.bitsPerSample = (info.formatFlags & MAC_FORMAT_FLAG_8_BIT) != 0 ? 8
        : ((info.formatFlags & MAC_FORMAT_FLAG_24_BIT) != 0 ? 24 : 16);
    info.wavHeaderLength = header.wavHeaderLength;
    info.wavTailLength = header.wavTailLength;
  }

  static class ApeDescriptor {
    public final static int DESCRIPTOR_LENGTH = 52;

    public @MonotonicNonNull String fileId;
    public int fileVersion;
    public int padding1;
    public long descriptorLength;
    public long headerLength;
    public long seekTableLength;
    public long wavHeaderLength;
    public long nAPEFrameDataBytes;
    public long nAPEFrameDataBytesHigh;
    public long wavTailLength;
    public byte[] cFileMD5 = new byte[0];

    public static ApeDescriptor read(final ExtractorInput input) throws IOException {
      ParsableByteArray scratch = new ParsableByteArray(DESCRIPTOR_LENGTH);
      input.peekFully(scratch.getData(), 0, DESCRIPTOR_LENGTH);
      ApeDescriptor descriptor = new ApeDescriptor();
      descriptor.fileId = scratch.readString(4, Charsets.US_ASCII);
      descriptor.fileVersion = scratch.readLittleEndianUnsignedShort();
      descriptor.padding1 = scratch.readLittleEndianUnsignedShort();
      descriptor.descriptorLength = scratch.readLittleEndianUnsignedInt();
      descriptor.headerLength = scratch.readLittleEndianUnsignedInt();
      descriptor.seekTableLength = scratch.readLittleEndianUnsignedInt();
      descriptor.wavHeaderLength = scratch.readLittleEndianUnsignedInt();
      descriptor.nAPEFrameDataBytes = scratch.readLittleEndianUnsignedInt();
      descriptor.nAPEFrameDataBytesHigh = scratch.readLittleEndianUnsignedInt();
      descriptor.wavTailLength = scratch.readLittleEndianUnsignedInt();
      descriptor.cFileMD5 = new byte[16];
      scratch.readBytes(descriptor.cFileMD5, 0, descriptor.cFileMD5.length);
      return descriptor;
    }
  }

  static class ApeHeaderV3980 {
    public final static int HEADER_LENGTH = 24;

    public int compressionLevel;
    public int formatFlags;
    public long blocksPerFrame;
    public long finalFrameBlocks;
    public long totalFrames;
    public int bitsPerSample;
    public int channels;
    public long sampleRate;

    public static ApeHeaderV3980 read(final ExtractorInput input) throws IOException {
      ParsableByteArray scratch = new ParsableByteArray(HEADER_LENGTH);
      input.peekFully(scratch.getData(), 0, HEADER_LENGTH);

      ApeHeaderV3980 header = new ApeHeaderV3980();
      header.compressionLevel = scratch.readLittleEndianUnsignedShort();
      header.formatFlags = scratch.readLittleEndianUnsignedShort();
      header.blocksPerFrame = scratch.readLittleEndianUnsignedInt();
      header.finalFrameBlocks = scratch.readLittleEndianUnsignedInt();
      header.totalFrames = scratch.readLittleEndianUnsignedInt();
      header.bitsPerSample = scratch.readLittleEndianUnsignedShort();
      header.channels = scratch.readLittleEndianUnsignedShort();
      header.sampleRate = scratch.readLittleEndianUnsignedInt();
      return header;
    }
  }

  static class ApeHeaderV0000 {
    public final static int HEADER_LENGTH = 32;

    public @MonotonicNonNull String fileId;
    public int fileVersion;
    public int compressionType;
    public int formatFlags;
    public int channels;
    public long sampleRate;
    public long wavHeaderLength;
    public long wavTailLength;
    public long totalFrames;
    public long finalFrameBlocks;

    public static ApeHeaderV0000 read(final ExtractorInput input) throws IOException {
      ParsableByteArray scratch = new ParsableByteArray(HEADER_LENGTH);
      input.peekFully(scratch.getData(), 0, HEADER_LENGTH);

      ApeHeaderV0000 header = new ApeHeaderV0000();
      header.fileId = scratch.readString(4, Charsets.US_ASCII);
      header.fileVersion = scratch.readLittleEndianUnsignedShort();
      header.compressionType = scratch.readLittleEndianUnsignedShort();
      header.formatFlags = scratch.readLittleEndianUnsignedShort();
      header.channels = scratch.readLittleEndianUnsignedShort();
      header.sampleRate = scratch.readLittleEndianUnsignedInt();
      header.wavHeaderLength = scratch.readLittleEndianUnsignedInt();
      header.wavTailLength = scratch.readLittleEndianUnsignedInt();
      header.totalFrames = scratch.readLittleEndianUnsignedInt();
      header.finalFrameBlocks = scratch.readLittleEndianUnsignedInt();
      return header;
    }
  }
}
