/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.CeaUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;

import java.util.List;

/** Consumes user data, outputting contained CEA-608/708 messages to a {@link TrackOutput}. */
/* package */ final class UserDataReader {

  private static final int USER_DATA_START_CODE = 0x0001B2;

  private final List<Format> closedCaptionFormats;
  private final TrackOutput[] outputs;
  private final Format[] formatsOutputted;
  private boolean updateDvdFormatTried;
  private final ParsableByteArray newData;
  private final ParsableByteArray rollovers;

  public UserDataReader(List<Format> closedCaptionFormats) {
    this.closedCaptionFormats = closedCaptionFormats;
    outputs = new TrackOutput[closedCaptionFormats.size()];
    formatsOutputted = new Format[closedCaptionFormats.size()];
    rollovers = new ParsableByteArray(new byte[3], 0);
    newData = new ParsableByteArray();
  }

  public void createTracks(
      ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
    for (int i = 0; i < outputs.length; i++) {
      idGenerator.generateNewId();
      TrackOutput output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
      Format channelFormat = closedCaptionFormats.get(i);
      @Nullable String channelMimeType = channelFormat.sampleMimeType;
      Assertions.checkArgument(
          MimeTypes.APPLICATION_CEA608.equals(channelMimeType)
              || MimeTypes.APPLICATION_CEA708.equals(channelMimeType),
          "Invalid closed caption MIME type provided: " + channelMimeType);
      Format format = (
          new Format.Builder()
              .setId(idGenerator.getFormatId())
              .setSampleMimeType(channelMimeType)
              .setSelectionFlags(channelFormat.selectionFlags)
              .setLanguage(channelFormat.language)
              .setAccessibilityChannel(channelFormat.accessibilityChannel)
              .setInitializationData(channelFormat.initializationData)
              .build());
      output.format(format);
      formatsOutputted[i] = format;
      outputs[i] = output;
    }
  }

  public void consume(long pesTimeUs, ParsableByteArray userDataPayload) {
    if (userDataPayload.bytesLeft() < 9) {
      return;
    }
    int userDataStartCode = userDataPayload.readInt();
    int userDataIdentifier = userDataPayload.readInt();
    int userDataTypeCode = userDataPayload.readUnsignedByte();
    if (userDataStartCode == USER_DATA_START_CODE
        && userDataIdentifier == CeaUtil.USER_DATA_IDENTIFIER_GA94
        && userDataTypeCode == CeaUtil.USER_DATA_TYPE_CODE_MPEG_CC) {
      CeaUtil.consumeCcData(pesTimeUs, userDataPayload, outputs);
    }
    // CC in DVD format
    if (userDataStartCode == USER_DATA_START_CODE
        && userDataIdentifier == 0x434301f8) {
      if (!updateDvdFormatTried) {
        updateDvdFormatTried = true;
        updateDvdFormat();
      }
      consumeCcData(pesTimeUs, userDataPayload, rollovers, newData, outputs);
    }
  }

  /* extract DVD CC data
   *
   * uint32_t   user_data_start_code        0x000001B2    (big endian)
   * uint16_t   user_identifier             0x4343 "CC"
   * uint8_t    user_data_type_code         0x01
   * uint8_t    caption_block_size          0xF8
   * uint8_t
   *   bit 7    caption_odd_field_first     1=odd field (CC1/CC2) first  0=even field (CC3/CC4) first
   *   bit 6    caption_filler              0
   *   bit 5:1  caption_block_count         number of caption blocks (pairs of caption words = frames). Most DVDs use 15 per start of GOP.
   *   bit 0    caption_extra_field_added   1=one additional caption word
   *
   * struct caption_field_block {
   *   uint8_t
   *     bit 7:1 caption_filler             0x7F (all 1s)
   *     bit 0   caption_field_odd          1=odd field (this is CC1/CC2)  0=even field (this is CC3/CC4)
   *   uint8_t   caption_first_byte
   *   uint8_t   caption_second_byte
   * } caption_block[(caption_block_count * 2) + caption_extra_field_added];
   *
   * Some DVDs encode caption data for both fields with caption_field_odd=1. The only way to decode the fields
   * correctly is to start on the field indicated by caption_odd_field_first and count between odd/even fields.
   * Don't assume that the first caption word is the odd field. There do exist MPEG files in the wild that start
   * on the even field. There also exist DVDs in the wild that encode an odd field count and the
   * caption_extra_field_added/caption_odd_field_first bits change per packet to allow that. */
  public static void consumeCcData(
      long presentationTimeUs,
      ParsableByteArray ccDataBuffer,
      ParsableByteArray rollovers,
      ParsableByteArray newData,
      TrackOutput[] outputs) {
    final int pData = 9, limit = ccDataBuffer.limit();
    byte flags = ccDataBuffer.getData()[pData - 1];
    if (rollovers.bytesLeft() > 0) {
      byte[] bytes = ccDataBuffer.getData();
    }
    if (newData.capacity() < limit + rollovers.capacity()) {
      newData.ensureCapacity(2 * Math.max(0x83, limit) + rollovers.capacity());
    }
    int limitNewData = 0;
    byte[] newBytes = newData.getData();
    if (rollovers.bytesLeft() > 0) {
      System.arraycopy(rollovers.getData(), 0, newBytes, 0, rollovers.bytesLeft());
      limitNewData = rollovers.bytesLeft();
      rollovers.reset(0);
    }
    System.arraycopy(ccDataBuffer.getData(), pData, newBytes, limitNewData, limit - pData);
    limitNewData += limit - pData;
    newData.reset(limitNewData);

    int ccCount = 0;
    // There is a caption count field in the data, but it is often
    // incorrect.  So count the number of captions present.
    for (int p = 0; p + 6 <= limitNewData && ((newBytes[p] & 0xfe) == 0xfe); p += 6) {
      ccCount++;
    }
    // Transform the DVD format into A53 Part 4 format
    int p = 0;
    if (ccCount > 0) {
      boolean field1 = (flags & 0x80) != 0;
      for (int i = 0; i < ccCount; i++) {
        newBytes[p + 0] = (byte) ((newBytes[p + 0] == 0xff && field1) ? 0xfc : 0xfd);
        newBytes[p + 3] = (byte) ((newBytes[p + 3] == 0xff && !field1) ? 0xfc : 0xfd);
        p += 6;
      }
    }
    int sampleLength = ccCount * 6;
    for (TrackOutput output : outputs) {
      newData.setPosition(0);
      output.sampleData(newData, sampleLength);
      checkState(presentationTimeUs != C.TIME_UNSET);
      output.sampleMetadata(
          presentationTimeUs,
          C.BUFFER_FLAG_KEY_FRAME,
          sampleLength,
          /* offset= */ 0,
          /* cryptoData= */ null);
    }
    int byteLeft = limitNewData - sampleLength;
    if (byteLeft > 0) {
      // assert byteLeft == 3;
      rollovers.ensureCapacity(byteLeft);
      System.arraycopy(
          newBytes, sampleLength,
          rollovers.getData(), 0, byteLeft);
      rollovers.reset(byteLeft);
    }
    ccDataBuffer.skipBytes(ccDataBuffer.bytesLeft());
  }

  private void updateDvdFormat() {
    for (int i = 0; i < outputs.length; i++) {
      if (formatsOutputted[i] == null) {
        continue;
      }
      if (formatsOutputted[i].language == null || formatsOutputted[i].language.startsWith("?")) {
        Format format = formatsOutputted[i].buildUpon()
            .setLanguage("cc")
            .build();
        outputs[i].format(format);
        formatsOutputted[i] = format;
      }
    }
  }
}
