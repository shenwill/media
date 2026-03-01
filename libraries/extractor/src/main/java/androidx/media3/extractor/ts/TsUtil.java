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

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;

/** Utilities method for extracting MPEG-TS streams. */
@UnstableApi
public final class TsUtil {

  public static long dvdTimeToUs(int dvdT) {
    return dvdTimeToUs((dvdT >> 24) & 0xff, (dvdT >> 16) & 0xff, (dvdT >> 8) & 0xff, dvdT & 0xff);
  }

  public static long dvdTimeToUs(int hour, int minute, int second, int frameUnit) {
    double[] frames_per_s = {-1.0, 25.00, -1.0, 30};
    int fpsIndex = (frameUnit & 0xc0) >> 6;
    double fps = frames_per_s[fpsIndex];
    long us;
    us = (((hour & 0xf0) >> 3) * 5 + (hour & 0x0f)) * 3600_000_000L;
    us += (((minute & 0xf0) >> 3) * 5 + (minute & 0x0f)) * 60_000_000L;
    us += (((second & 0xf0) >> 3) * 5 + (second & 0x0f)) * 1_000_000L;

    if (fps > 0) {
      us += (((frameUnit & 0x30) >> 3) * 5 +
          (frameUnit & 0x0f)) * 1000_000.0 / fps;
    }
    return fpsIndex == 3 ? us * 1001 / 1000 : us;
  }

  /**
   * Returns whether a TS packet starts at {@code searchPosition} according to the MPEG-TS
   * synchronization recommendations.
   *
   * <p>ISO/IEC 13818-1:2015 Annex G recommends that 5 sync bytes emulating the start of 5
   * consecutive TS packets should never occur as part of the TS packets' contents. So, this method
   * returns true when {@code data} contains a sync byte at {@code searchPosition}, and said sync
   * byte is also one of five consecutive sync bytes separated from each other by the size of a TS
   * packet.
   *
   * @param data The array holding the data to search in.
   * @param start The first valid position in {@code data} from which a sync byte can be read.
   * @param limit The first invalid position in {@code data}, after which no data should be read.
   * @param searchPosition The position to check for a TS packet start.
   * @return Whether a TS packet starts at {@code searchPosition}.
   */
  public static boolean isStartOfTsPacket(
      byte[] data, int start, int limit, int searchPosition, int packetSize, int prefixSize) {
    int consecutiveSyncByteCount = 0;
    for (int i = -4; i <= 4; i++) {
      int currentPosition = searchPosition + i * packetSize;
      if (currentPosition < start
          || currentPosition >= limit
          || data[currentPosition] != TsExtractor.TS_SYNC_BYTE) {
        consecutiveSyncByteCount = 0;
      } else if (++consecutiveSyncByteCount == 5) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the position of the first TS_SYNC_BYTE within the range [startPosition, limitPosition)
   * from the provided data array, or returns limitPosition if sync byte could not be found.
   * Use this function carefully because the first TS_SYNC_BYTE maybe not the real TS_SYNC_BYTE
   * Following usages are safe:
   * 1. Use this function stepping forward again and again at least 5 times,
   * 2. or use this function after real TS_SYNC_BYTE is found.
   */
  public static int findFirstSyncBytePosition(byte[] data, int startPosition, int limitPosition) {
    int position = startPosition;
    while (position < limitPosition && data[position] != TsExtractor.TS_SYNC_BYTE) {
      position++;
    }
    return position;
  }

  public static int tryToFindRealSyncBytePosition(
      byte[] data, int startPosition, int limitPosition, int packetSize) {
    int position = startPosition;
    while (position < limitPosition) {
      if (data[position] != TsExtractor.TS_SYNC_BYTE) {
        position++;
      } else {
        int roundCheck = 1;
        int nextSyncPosition = packetSize + position;
        while (roundCheck < 5 && nextSyncPosition < limitPosition
            && data[nextSyncPosition] == TsExtractor.TS_SYNC_BYTE) {
          roundCheck++;
          nextSyncPosition += packetSize;
        }
        if (roundCheck == 5 || nextSyncPosition >= limitPosition) {
          break;
        } else {
          position++;
        }
      }
    }
    return position;
  }

  public static int getStartPosition(int syncBytePosition, int packetSize) {
    return syncBytePosition - (packetSize - TsExtractor.TS_PACKET_SIZE);
  }

  /**
   * Returns the PCR value read from a given TS packet.
   *
   * @param packetBuffer The buffer that holds the packet.
   * @param startOfPacket The starting position of the packet in the buffer.
   * @param pcrPid The PID for valid packets that contain PCR values.
   * @return The PCR value read from the packet, if its PID is equal to {@code pcrPid} and it
   *     contains a valid PCR value. Returns {@link C#TIME_UNSET} otherwise.
   */
  public static long readPcrFromPacket(
      ParsableByteArray packetBuffer, int startOfPacket, int pcrPid) {
    packetBuffer.setPosition(startOfPacket);
    if (packetBuffer.bytesLeft() < 5) {
      // Header = 4 bytes, adaptationFieldLength = 1 byte.
      return C.TIME_UNSET;
    }
    // Note: See ISO/IEC 13818-1, section 2.4.3.2 for details of the header format.
    int tsPacketHeader = packetBuffer.readInt();
    if ((tsPacketHeader & 0x800000) != 0) {
      // transport_error_indicator != 0 means there are uncorrectable errors in this packet.
      return C.TIME_UNSET;
    }
    int pid = (tsPacketHeader & 0x1FFF00) >> 8;
    if (pid != pcrPid) {
      return C.TIME_UNSET;
    }
    boolean adaptationFieldExists = (tsPacketHeader & 0x20) != 0;
    if (!adaptationFieldExists) {
      return C.TIME_UNSET;
    }

    int adaptationFieldLength = packetBuffer.readUnsignedByte();
    if (adaptationFieldLength >= 7 && packetBuffer.bytesLeft() >= 7) {
      int flags = packetBuffer.readUnsignedByte();
      boolean pcrFlagSet = (flags & 0x10) == 0x10;
      if (pcrFlagSet) {
        byte[] pcrBytes = new byte[6];
        packetBuffer.readBytes(pcrBytes, /* offset= */ 0, pcrBytes.length);
        return readPcrValueFromPcrBytes(pcrBytes);
      }
    }
    return C.TIME_UNSET;
  }

  /**
   * Returns the value of PCR base - first 33 bits in big endian order from the PCR bytes.
   *
   * <p>We ignore PCR Ext, because it's too small to have any significance.
   */
  private static long readPcrValueFromPcrBytes(byte[] pcrBytes) {
    return (pcrBytes[0] & 0xFFL) << 25
        | (pcrBytes[1] & 0xFFL) << 17
        | (pcrBytes[2] & 0xFFL) << 9
        | (pcrBytes[3] & 0xFFL) << 1
        | (pcrBytes[4] & 0xFFL) >> 7;
  }

  private TsUtil() {
    // Prevent instantiation.
  }
}
