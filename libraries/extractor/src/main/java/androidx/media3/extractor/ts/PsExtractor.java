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

import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseLongArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import com.google.common.primitives.Ints;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

import java.io.IOException;
import java.util.List;

/** Extracts data from the MPEG-2 PS container format. */
@UnstableApi
public final class PsExtractor implements Extractor {

  private static final String TAG = "PsExtractor";

  /** Factory for {@link PsExtractor} instances. */
  public static final ExtractorsFactory FACTORY = () -> new Extractor[] {new PsExtractor()};

  /* package */ static final int PACK_START_CODE = 0x000001BA;
  /* package */ static final int SYSTEM_HEADER_START_CODE = 0x000001BB;
  /* package */ static final int PACKET_START_CODE_PREFIX = 0x000001;
  /* package */ static final int MPEG_PROGRAM_END_CODE = 0x000001B9;
  /* package */ static final int PRIVATE_STREAM_2_START_CODE = 0x000001BF;
  private static final int MAX_STREAM_ID_PLUS_ONE = 0x100;

  // Max search length for first audio and video track in input data.
  private static final long MAX_SEARCH_LENGTH = 1024 * 1024;
  // Max search length for additional audio and video tracks in input data after at least one audio
  // and video track has been found.
  private static final long MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND = 8 * 1024;

  public static final int PRIVATE_STREAM_1 = 0xBD;
  public static final int PADDING_STREAM = 0xBE;

  public static final int AUDIO_STREAM = 0xC0;
  public static final int AUDIO_STREAM_MASK = 0xE0;
  public static final int VIDEO_STREAM = 0xE0;
  public static final int VIDEO_STREAM_MASK = 0xF0;

  private final TimestampAdjuster timestampAdjuster;
  private final SparseArray<PesReader> psPayloadReaders; // Indexed by pid
  private final ParsableByteArray psPacketBuffer;
  private final PsDurationReader durationReader;
  private final long durationUs;
  private final byte[][] initDataBytes;
  private final byte[] timeMapTableBytes;
  private ElementaryStreamReaderStub elementaryStreamReaderStub;
  private final PrivateStream2Reader privateStream2Reader;
  private final SparseLongArray cellsWithStartTimes;
  private boolean skipInterleavedVobU;
  private long timeUsFromVobU = C.TIME_UNSET;
  final private SparseBooleanArray timeOffsetNeedsUpdateArray = new SparseBooleanArray();
  final private SparseLongArray timeOffsetUsBetweenVobUAndPesArray = new SparseLongArray();
  private final byte[] videoAttrBytes;
  private long lastOffset = C.TIME_UNSET;
  private boolean foundAllTracks;
  private boolean foundAudioTrack;
  private boolean foundVideoTrack;
  private long lastTrackPosition;

  // Accessed only by the loading thread.
  @Nullable private PsBinarySearchSeeker psBinarySearchSeeker;
  private @MonotonicNonNull ExtractorOutput output;
  private boolean hasOutputSeekMap;

  public PsExtractor() {
    this(null);
  }

  public PsExtractor(Bundle info) {
    this(new TimestampAdjuster(0), info);
  }

  public PsExtractor(TimestampAdjuster timestampAdjuster, Bundle info) {
    this.timestampAdjuster = timestampAdjuster;
    psPacketBuffer = new ParsableByteArray(4096);
    psPayloadReaders = new SparseArray<>();

    initDataBytes = info != null
        ? Util.splitBytes("iniD", info.getByteArray("initDataBytes")) : null;
    durationUs = getDurationUsFromInfo(initDataBytes);
    //Log.i(TAG, "```from bundle info duration=" + (durationUs / 1000000));
    durationReader = durationUs == C.TIME_UNSET ? new PsDurationReader() : null;
    //durationReader = new PsDurationReader();
    privateStream2Reader = new PrivateStream2Reader();
    videoAttrBytes = initDataBytes != null ? initDataBytes[2] : null;
    cellsWithStartTimes = getCellsWithStartTimes(initDataBytes);
    timeMapTableBytes = getTimeMapTableBytes(initDataBytes);
  }

  // Extractor implementation.

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    byte[] scratch = new byte[14];
    input.peekFully(scratch, 0, 14);

    // Verify the PACK_START_CODE for the first 4 bytes
    if (PACK_START_CODE
        != (((scratch[0] & 0xFF) << 24)
            | ((scratch[1] & 0xFF) << 16)
            | ((scratch[2] & 0xFF) << 8)
            | (scratch[3] & 0xFF))) {
      return false;
    }
    // Verify the 01xxx1xx marker on the 5th byte
    if ((scratch[4] & 0xC4) != 0x44) {
      return false;
    }
    // Verify the xxxxx1xx marker on the 7th byte
    if ((scratch[6] & 0x04) != 0x04) {
      return false;
    }
    // Verify the xxxxx1xx marker on the 9th byte
    if ((scratch[8] & 0x04) != 0x04) {
      return false;
    }
    // Verify the xxxxxxx1 marker on the 10th byte
    if ((scratch[9] & 0x01) != 0x01) {
      return false;
    }
    // Verify the xxxxxx11 marker on the 13th byte
    if ((scratch[12] & 0x03) != 0x03) {
      return false;
    }
    // Read the stuffing length from the 14th byte (last 3 bits)
    int packStuffingLength = scratch[13] & 0x07;
    input.advancePeekPosition(packStuffingLength);
    // Now check that the next 3 bytes are the beginning of an MPEG start code
    input.peekFully(scratch, 0, 3);
    return (PACKET_START_CODE_PREFIX
        == (((scratch[0] & 0xFF) << 16) | ((scratch[1] & 0xFF) << 8) | (scratch[2] & 0xFF)));
  }

  @Override
  public void init(ExtractorOutput output) {
    this.output = output;
  }

  @Override
  public void seek(long position, long timeUs) {
    // If the timestamp adjuster has not yet established a timestamp offset, we need to reset its
    // expected first sample timestamp to be the new seek position. Without this, the timestamp
    // adjuster would incorrectly establish its timestamp offset assuming that the first sample
    // after this seek corresponds to the start of the stream (or a previous seek position, if there
    // was one).
    boolean resetTimestampAdjuster = timestampAdjuster.getTimestampOffsetUs() == C.TIME_UNSET;
    if (!resetTimestampAdjuster) {
      long adjusterFirstSampleTimestampUs = timestampAdjuster.getFirstSampleTimestampUs();
      // Also reset the timestamp adjuster if its offset was calculated based on a non-zero position
      // in the stream (other than the position being seeked to), since in this case the offset may
      // not be accurate.
      resetTimestampAdjuster =
          adjusterFirstSampleTimestampUs != C.TIME_UNSET
              && adjusterFirstSampleTimestampUs != 0
              && adjusterFirstSampleTimestampUs != timeUs;
    }
    if (resetTimestampAdjuster) {
      timestampAdjuster.reset(timeUs);
    }

    if (psBinarySearchSeeker != null) {
      psBinarySearchSeeker.setSeekTargetUs(timeUs);
    }
    for (int i = 0; i < psPayloadReaders.size(); i++) {
      psPayloadReaders.valueAt(i).seek();
    }
    skipInterleavedVobU = false;
  }

  @Override
  public void release() {
    // Do nothing
  }

  @Override
  public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
    Assertions.checkStateNotNull(output); // Asserts init has been called.

    long inputLength = input.getLength();
    boolean canReadDuration = durationReader != null && inputLength != C.LENGTH_UNSET;
    if (canReadDuration && !durationReader.isDurationReadFinished()) {
      return durationReader.readDuration(input, seekPosition);
    }
    maybeOutputSeekMap(inputLength);
    if (psBinarySearchSeeker != null && psBinarySearchSeeker.isSeeking()) {
      return psBinarySearchSeeker.handlePendingSeek(input, seekPosition);
    }

    input.resetPeekPosition();
    long peekBytesLeft =
        inputLength != C.LENGTH_UNSET ? inputLength - input.getPeekPosition() : C.LENGTH_UNSET;
    if (peekBytesLeft != C.LENGTH_UNSET && peekBytesLeft < 4) {
      return RESULT_END_OF_INPUT;
    }
    // First peek and check what type of start code is next.
    if (!input.peekFully(psPacketBuffer.getData(), 0, 4, true)) {
      return RESULT_END_OF_INPUT;
    }

    psPacketBuffer.setPosition(0);
    int nextStartCode = psPacketBuffer.readInt();
    if (nextStartCode == MPEG_PROGRAM_END_CODE) {
      return RESULT_END_OF_INPUT;
    } else if (nextStartCode == PACK_START_CODE) {
      // Now peek the rest of the pack_header.
      input.peekFully(psPacketBuffer.getData(), 0, 10);

      // We only care about the pack_stuffing_length in here, skip the first 77 bits.
      psPacketBuffer.setPosition(9);

      // Last 3 bits is the length.
      int packStuffingLength = psPacketBuffer.readUnsignedByte() & 0x07;

      // Now skip the stuffing and the pack header.
      input.skipFully(packStuffingLength + 14);
      return RESULT_CONTINUE;
    } else if (nextStartCode == SYSTEM_HEADER_START_CODE) {
      // We just skip all this, but we need to get the length first.
      input.peekFully(psPacketBuffer.getData(), 0, 2);

      // Length is the next 2 bytes.
      psPacketBuffer.setPosition(0);
      int systemHeaderLength = psPacketBuffer.readUnsignedShort();
      input.skipFully(systemHeaderLength + 6);
      return RESULT_CONTINUE;
    } else if (nextStartCode == PRIVATE_STREAM_2_START_CODE) {
      privateStream2Reader.processInput(input);
      return RESULT_CONTINUE;
    } else if (((nextStartCode & 0xFFFFFF00) >> 8) != PACKET_START_CODE_PREFIX) {
      input.skipFully(1); // Skip bytes until we see a valid start code again.
      return RESULT_CONTINUE;
    }

    // We're at the start of a regular PES packet now.
    // Get the stream ID off the last byte of the start code.
    int streamId = nextStartCode & 0xFF;

    // Check to see if we have this one in our map yet, and if not, then add it.
    PesReader payloadReader = psPayloadReaders.get(streamId);
    if (!foundAllTracks) {
      if (payloadReader == null) {
        @Nullable ElementaryStreamReader elementaryStreamReader = null;
        if (streamId == PRIVATE_STREAM_1) {
          // Private stream, used for AC3 audio.
          // NOTE: This may need further parsing to determine if its DTS, but that's likely only
          // valid for DVDs.
//          foundAudioTrack = true;
          elementaryStreamReaderStub = new ElementaryStreamReaderStub(initDataBytes);
          elementaryStreamReader = elementaryStreamReaderStub;
          lastTrackPosition = input.getPosition();
        } else if ((streamId & AUDIO_STREAM_MASK) == AUDIO_STREAM) {
          elementaryStreamReader = new MpegAudioReader();
          foundAudioTrack = true;
          lastTrackPosition = input.getPosition();
        } else if ((streamId & VIDEO_STREAM_MASK) == VIDEO_STREAM) {
          boolean field1cc = (videoAttrBytes != null) && (videoAttrBytes[1] & 0x80) != 0;
          boolean field2cc = (videoAttrBytes != null) && (videoAttrBytes[1] & 0x40) != 0;
          UserDataReader userDataReader = field1cc || field2cc
              ? new UserDataReader(
              List.of(
                  new Format.Builder()
                      .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                      .setLanguage("?CC") // updateDvdFormat() will setLanguage("cc") later
                      .setAccessibilityChannel(field1cc ? 3 : 1)
                      .setInitializationData(null)
                      .build()
              ))
              : null;
          elementaryStreamReader = new H262Reader(userDataReader, initDataBytes, true);
          foundVideoTrack = true;
          lastTrackPosition = input.getPosition();
        } else if (streamId != PADDING_STREAM) {
          android.util.Log.i(TAG, "---===streamId not handled: 0x" + Integer.toHexString(streamId));
        }
        if (elementaryStreamReader != null) {
          TrackIdGenerator idGenerator = new TrackIdGenerator(streamId, MAX_STREAM_ID_PLUS_ONE);
          elementaryStreamReader.createTracks(output, idGenerator);
          payloadReader = new PesReader(elementaryStreamReader, streamId, timestampAdjuster);
          psPayloadReaders.put(streamId, payloadReader);
        }
      }
      long maxSearchPosition =
          foundAudioTrack && foundVideoTrack
              ? lastTrackPosition + MAX_SEARCH_LENGTH_AFTER_AUDIO_AND_VIDEO_FOUND
              : MAX_SEARCH_LENGTH;
      if (input.getPosition() > maxSearchPosition) {
        foundAllTracks = true;
        if (elementaryStreamReaderStub != null) {
          PesReader privatePayloadReader = psPayloadReaders.get(PRIVATE_STREAM_1);
          if (privatePayloadReader != null) {
            elementaryStreamReaderStub.endTracks(privatePayloadReader.pesPayloadReaders);
          }
        }
        output.endTracks();
      }
    }

    // The next 2 bytes are the length. Once we have that we can consume the complete packet.
    input.peekFully(psPacketBuffer.getData(), 0, 2);
    psPacketBuffer.setPosition(0);
    int payloadLength = psPacketBuffer.readUnsignedShort();
    int pesLength = payloadLength + 6;

    if (skipInterleavedVobU || payloadReader == null) {
      // Just skip this data.
      input.skipFully(pesLength);
    } else {
      psPacketBuffer.reset(pesLength);
      // Read the whole packet and the header for consumption.
      input.readFully(psPacketBuffer.getData(), 0, pesLength);
      psPacketBuffer.setPosition(6);
      payloadReader.consume(psPacketBuffer);
      psPacketBuffer.setLimit(psPacketBuffer.capacity());
    }

    return RESULT_CONTINUE;
  }

  public void setTimeUsFromVobU(long timeUsFromVobU) {
    this.timeUsFromVobU = timeUsFromVobU;
    updateValueInSparseBooleanArray(true, timeOffsetNeedsUpdateArray);
  }

  public static void updateValueInSparseBooleanArray(boolean value, SparseBooleanArray array) {
    for (int i = 0; i < array.size(); i++) {
      array.put(array.keyAt(i), value);
    }
  }

  public void unsetTimeUsFromVobU() {
    this.timeUsFromVobU = C.TIME_UNSET;
    updateValueInSparseBooleanArray(false, timeOffsetNeedsUpdateArray);
  }

  // Internals.

  private SparseLongArray getCellsWithStartTimes(byte[][] initDataBytes) {
    byte[] bytes = initDataBytes != null ? initDataBytes[8] : null;
    if (bytes == null) {
      return null;
    }
    assert bytes.length % 11 == 0;
    int size = bytes.length / 11;
    ParsableByteArray ba = new ParsableByteArray(bytes);
    SparseLongArray cells = new SparseLongArray(size);
    for (int i = 0; i < size; i++) {
      int vobIdNrCellNr = ba.readInt24();
      cells.put(vobIdNrCellNr, ba.readUnsignedLongToLong());
    }
    return cells;
  }

  private long getDurationUsFromInfo(byte[][] initDataBytes) {
    byte[] bytes = initDataBytes != null ? initDataBytes[6] : null;
    if (bytes == null || bytes.length < 4) {
      return C.TIME_UNSET;
    }
    return TsUtil.dvdTimeToUs(Ints.fromBytes(bytes[0], bytes[1], bytes[2], bytes[3]));
  }

  private byte[] getTimeMapTableBytes(byte[][] initDataBytes) {
    byte[] bytes = initDataBytes != null ? initDataBytes[9] : null;
    if (bytes == null || bytes.length < 11) {
      return null;
    }
    return bytes;
  }

  @RequiresNonNull("output")
  private void maybeOutputSeekMap(long inputLength) {
    if (!hasOutputSeekMap) {
      hasOutputSeekMap = true;
      if (timeMapTableBytes != null && timeMapTableBytes.length > 0) {
        output.seekMap(new DvdTimeSeeker(timeMapTableBytes, durationUs).getSeekMap());
        return;
      }
      long durationUs = durationReader != null ? durationReader.getDurationUs() : this.durationUs;
      TimestampAdjuster scrTimestampAdjuster = getTimestampAdjuster(durationUs);
      if (durationUs != C.TIME_UNSET) {
        psBinarySearchSeeker =
            new PsBinarySearchSeeker(
                scrTimestampAdjuster,
                durationUs,
                inputLength);
        output.seekMap(psBinarySearchSeeker.getSeekMap());
      } else {
        output.seekMap(new SeekMap.Unseekable(durationUs));
      }
    }
  }

  @NonNull
  private TimestampAdjuster getTimestampAdjuster(long durationUs) {
    TimestampAdjuster scrTimestampAdjuster;
    if (durationReader != null) {
      scrTimestampAdjuster = durationReader.getScrTimestampAdjuster();
    } else {
      scrTimestampAdjuster = new TimestampAdjuster(/* firstSampleTimestampUs= */ 0);
      scrTimestampAdjuster.adjustTsTimestamp(0);
      if (durationUs != C.TIME_UNSET) {
        scrTimestampAdjuster.adjustTsTimestampGreaterThanPreviousTimestamp(
            TimestampAdjuster.usToNonWrappedPts(durationUs));
      }
    }
    return scrTimestampAdjuster;
  }

  /** Parses PES packet data and extracts samples. */
  private final class PesReader {

    private static final int PES_SCRATCH_SIZE = 64;

    private final SparseArray<ElementaryStreamReader> pesPayloadReaders;
    private final ElementaryStreamReader pesPayloadReader;
    private final TimestampAdjuster timestampAdjuster;
    private final ParsableBitArray pesScratch;
    private final int streamId;

    private boolean ptsFlag;
    private boolean dtsFlag;
    private boolean seenFirstDts;
    private int extendedHeaderLength;
    private long timeUs;

    public PesReader(
        ElementaryStreamReader pesPayloadReader,
        int streamId,
        TimestampAdjuster timestampAdjuster) {
      this.pesPayloadReader = pesPayloadReader;
      this.streamId = streamId;
      this.timestampAdjuster = timestampAdjuster;
      pesScratch = new ParsableBitArray(new byte[PES_SCRATCH_SIZE]);
      pesPayloadReaders = pesPayloadReader instanceof ElementaryStreamReaderStub
          ? new SparseArray<>() : null;
    }

    /**
     * Notifies the reader that a seek has occurred.
     *
     * <p>Following a call to this method, the data passed to the next invocation of {@link
     * #consume(ParsableByteArray)} will not be a continuation of the data that was previously
     * passed. Hence the reader should reset any internal state.
     */
    public void seek() {
      seenFirstDts = false;
      if (pesPayloadReader instanceof ElementaryStreamReaderStub) {
        for (int i = 0; i < pesPayloadReaders.size(); i++) {
          ElementaryStreamReader reader = pesPayloadReaders.valueAt(i);
          reader.seek();
        }
      } else {
        pesPayloadReader.seek();
      }
    }

    /**
     * Consumes the payload of a PS packet.
     *
     * @param data The PES packet. The position will be set to the start of the payload.
     * @throws ParserException If the payload could not be parsed.
     */
    public void consume(ParsableByteArray data) throws ParserException {
      data.readBytes(pesScratch.data, 0, 3);
      pesScratch.setPosition(0);
      parseHeader();
      data.readBytes(pesScratch.data, 0, extendedHeaderLength);
      pesScratch.setPosition(0);
      parseHeaderExtension();
      ElementaryStreamReader pesPayloadReader = this.pesPayloadReader;
      int streamId = this.streamId;
      if (pesPayloadReader instanceof ElementaryStreamReaderStub) {
        int startCode = data.readUnsignedByte();
        streamId = startCode;
        // AC3 reader need to skip these 3 bytes, but not for VobSub reader
        ElementaryStreamReaderStub stub = (ElementaryStreamReaderStub) pesPayloadReader;
        pesPayloadReader = pesPayloadReaders.get(startCode);
        if (pesPayloadReader == null) {
          pesPayloadReader = stub.applyForReader(startCode);
          if (pesPayloadReader != null) {
            pesPayloadReader.createTracks(stub.extractorOutput, stub.idGenerator);
            pesPayloadReaders.put(startCode, pesPayloadReader);
          } else {
            return;
          }
        }
        if (!(pesPayloadReader instanceof VobsubReader)) {
          data.skipBytes(3);
        }
      }

      if (timeOffsetNeedsUpdateArray.indexOfKey(streamId) < 0) {
        timeOffsetNeedsUpdateArray.put(streamId, true);
      }
      if (timeOffsetUsBetweenVobUAndPesArray.indexOfKey(streamId) < 0) {
        timeOffsetUsBetweenVobUAndPesArray.put(streamId, C.TIME_UNSET);
      }
      long timeOffsetUsBetweenVobUAndPes = timeOffsetUsBetweenVobUAndPesArray.get(streamId);
      if (timeUs != C.TIME_UNSET) {
        if (timeUsFromVobU != C.TIME_UNSET && timeOffsetNeedsUpdateArray.get(streamId)) {
          timeOffsetUsBetweenVobUAndPes = timeUs - timeUsFromVobU;
          //logOffsetWithVobU(streamId, timeUs, timeUsFromVobU, timeOffsetUsBetweenVobUAndPes);
          timeOffsetUsBetweenVobUAndPesArray.put(streamId, timeOffsetUsBetweenVobUAndPes);
          lastOffset = timeOffsetUsBetweenVobUAndPes;
          timeUs = timeUsFromVobU;
        } else {
          if (timeOffsetUsBetweenVobUAndPes != C.TIME_UNSET) {
            //logTimeUsAlignWithVobU(streamId, timeUs, timeOffsetUsBetweenVobUAndPes);
            timeUs = timeUs - timeOffsetUsBetweenVobUAndPes;
          }
        }
        timeOffsetNeedsUpdateArray.put(streamId, false);
      }
      long timeUs = this.timeUs;
      // for audio tracks, subtitle tracks, use video timestamp
      if (streamId != VIDEO_STREAM) {
        // timeUs = getTimeUsFromVideoStreamReader(timeUs);
      }
      pesPayloadReader.packetStarted(timeUs, TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR);
      pesPayloadReader.consume(data);
      // We always have complete PES packets with program stream.
      pesPayloadReader.packetFinished(/* isEndOfInput= */ false);
    }

    private void logOffsetWithVobU(
        int streamId, long timeUs, long timeUsFromVobU, long timeOffsetUsBetweenVobUAndPes) {
      if (Math.abs(lastOffset - timeOffsetUsBetweenVobUAndPes) > 20_000) {
        Log.i(TAG, "```timeOffsetUsBetweenVobUAndPes="
            + Util.timeString(timeOffsetUsBetweenVobUAndPes / 1000)
            + " timeUs=" + Util.timeString(timeUs / 1000)
            + " timeUsFromVobU=" + Util.timeString(timeUsFromVobU / 1000)
            + " streamId=0x" + Integer.toHexString(streamId));
      }
    }

    private void logTimeUsAlignWithVobU(
        int streamId, long timeUs, long timeOffsetUsBetweenVobUAndPes) {
      Log.i(TAG, "```" + Integer.toHexString(streamId)
          + " PTSTime=" + Util.timeString(timeUs / 1000)
          + " timeOffsetUsBetweenVobUAndPes="
          + Util.timeString(timeOffsetUsBetweenVobUAndPes / 1000)
          + " PTS - timeOffsetUsBetweenVobUAndPes="
          + Util.timeString((timeUs - timeOffsetUsBetweenVobUAndPes) / 1000));
    }

    private void parseHeader() {
      // Note: see ISO/IEC 13818-1, section 2.4.3.6 for detailed information on the format of
      // the header.
      // First 8 bits are skipped: '10' (2), PES_scrambling_control (2), PES_priority (1),
      // data_alignment_indicator (1), copyright (1), original_or_copy (1)
      pesScratch.skipBits(8);
      ptsFlag = pesScratch.readBit();
      dtsFlag = pesScratch.readBit();
      // ESCR_flag (1), ES_rate_flag (1), DSM_trick_mode_flag (1),
      // additional_copy_info_flag (1), PES_CRC_flag (1), PES_extension_flag (1)
      pesScratch.skipBits(6);
      extendedHeaderLength = pesScratch.readBits(8);
    }

    private void parseHeaderExtension() {
      // to prevent timeUs from stopping stepping forward
      timeUs = C.TIME_UNSET;
      if (ptsFlag) {
        pesScratch.skipBits(4); // '0010' or '0011'
        long pts = (long) pesScratch.readBits(3) << 30;
        pesScratch.skipBits(1); // marker_bit
        pts |= pesScratch.readBits(15) << 15;
        pesScratch.skipBits(1); // marker_bit
        pts |= pesScratch.readBits(15);
        pesScratch.skipBits(1); // marker_bit
        if (!seenFirstDts && dtsFlag) {
          pesScratch.skipBits(4); // '0011'
          long dts = (long) pesScratch.readBits(3) << 30;
          pesScratch.skipBits(1); // marker_bit
          dts |= pesScratch.readBits(15) << 15;
          pesScratch.skipBits(1); // marker_bit
          dts |= pesScratch.readBits(15);
          pesScratch.skipBits(1); // marker_bit
          // Subsequent PES packets may have earlier presentation timestamps than this one, but they
          // should all be greater than or equal to this packet's decode timestamp. We feed the
          // decode timestamp to the adjuster here so that in the case that this is the first to be
          // fed, the adjuster will be able to compute an offset to apply such that the adjusted
          // presentation timestamps of all future packets are non-negative.
          timestampAdjuster.adjustTsTimestamp(dts);
          seenFirstDts = true;
        }
        timeUs = timestampAdjuster.adjustTsTimestamp(pts);
      }
    }
  }

  private class PrivateStream2Reader {

    private static final String TAG = "PrivateStream2Reader";
    private final ParsableByteArray ba = new ParsableByteArray(0x400);

    public void processInput(ExtractorInput input) throws IOException {
      long inputPos = input.getPosition();
      input.skipFully(4); // PRIVATE_STREAM_2_START_CODE;
      input.readFully(ba.getData(), 0, 2);
      ba.reset(2);
      int packetSize = ba.readUnsignedShort();
      ba.ensureCapacity(packetSize);
      input.readFully(ba.getData(), 0, packetSize);
      ba.reset(packetSize);

      if (cellsWithStartTimes == null) {
        skipInterleavedVobU = false;
        return;
      }

      int subStreamId = ba.readUnsignedByte();
      if (subStreamId == 0) { // PCI
        long logicBlockNr = ba.readUnsignedInt(); // Logical Block Number (sector) of this block
        ba.skipBytes(2 + 2 + 4); // vobu_cat flags, reserved, vobu_uop_ctl
        long ptsStart = ba.readUnsignedInt(); // Vobu Start Presentation Time (90KHz clk)
        long ptsEnd = ba.readUnsignedInt(); // Vobu End Presentation Time, vobu_e_ptm
        long ptsEndSE = ba.readUnsignedInt(); // End PTM of VOBU if Sequence_End_Code, vobu_se_e_ptm
        long cellElapsed = ba.readUnsignedInt(); // cell elapsed time in BCD, hh:mm:ss:ff, c_eltm
        String msg = "PCI"
            + " logicBlockNr=" + logicBlockNr
            + " ptsStart=" + ptsStart
            + " ptsEnd=" + ptsEnd
            + " ptsEndSE=" + ptsEndSE
            + " cellElapsed=" + Long.toHexString(cellElapsed & 0xffffff3f);
        // android.util.Log.i(TAG, "```" + msg);
      } else if (subStreamId == 1) { // DSI
        long systemClockReference = ba.readInt();
        long logicBlockNr = ba.readUnsignedInt();
        long endAddress = ba.readInt(); // VOBU end address: relative offset to last sector of VOBU
        ba.skipBytes(4 + 4 + 4); // for fast playing: 1st/2nd/3r reference frame end block, relative
        int vobNr = ba.readShort(); // VOBU number, vobu_vob_idn
        ba.skipBytes(1);
        int cellNr = ba.readUnsignedByte(); // CELL number within VOB
        int cellElapsed = ba.readInt(); // cell elapsed time in BCD, hh:mm:ss:ff, c_eltm
        // Interleaved Unit flags, bit 15: PREU flag, 14: ILVU flag, 13: Unit_Start, 12: Unit_End
        // 13/12: set for the first/last VOBU for a given angle or scene within a ILVU,
        // or the first/last VOBU in the preparation (PREU) sequence
        int flags = (ba.readShort() >> 12) & 0xf;
        boolean isInInterleave = (flags & 0x4) != 0;
        // ILVU end address: relative offset to the last sector within this ILVU for this angle or
        // scene. 00 00 00 00 for PREU and non-interleaved blocks
        long ilvuEndAddress = ba.readUnsignedInt();
        // relative offset to the next ILVU block (not VOBU) for this angle or scene.
        // 00 00 00 00 for PREU and non-interleaved blocks
        // ff ff ff ff for the last interleaved block, indicating the end of interleaving
        long nextIlvuStartAddress = ba.readUnsignedInt();
        // size of the next ILVU block for this angle or scene.
        // 00 00 for PREU and non-interleaved blocks
        // ff ff for the last interleaved block, indicating the end of interleaving
        long nextIlvuSize = ba.readShort();
        int vobIdNrCellNr = (vobNr << 8) | cellNr;
        long cellStartTime = cellsWithStartTimes.get(vobIdNrCellNr, C.TIME_UNSET);
        if (cellStartTime != C.TIME_UNSET) {
          setTimeUsFromVobU(cellStartTime + TsUtil.dvdTimeToUs(cellElapsed));
          skipInterleavedVobU = false;
        } else {
          unsetTimeUsFromVobU();
          skipInterleavedVobU = true;
        }

        if (lastVobNr != vobNr || lastCellNr != cellNr) {
          String msg = "```DSI input=0x" + Long.toHexString(inputPos)
              + " systemClockReference=" + systemClockReference
              + " skip=" + (skipInterleavedVobU ? "1" : "0")
              + " flags=0b" + Integer.toBinaryString(flags)
              + " logicBlockNr=" + logicBlockNr
              + " endAddress=" + endAddress
              + " vobNr=" + vobNr
              + " cellNr=" + cellNr
              + " cellElapsed=0x" + Long.toHexString(cellElapsed & 0x3f)
              + " ilvuEndAddress=" + ilvuEndAddress
              + " nextIlvuStartAddress=" + nextIlvuStartAddress
              + " nextIlvuSize=" + nextIlvuSize
              + " timeUsFromVobU=" + Util.timeString(timeUsFromVobU / 1000);
          // android.util.Log.i(TAG, msg);
        }
        lastVobNr = vobNr;
        lastCellNr = cellNr;
      }
    }

    private int lastVobNr, lastCellNr;
  }
}
