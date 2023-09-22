/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.extractor.avi;

import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.ParsableByteArray;

/** Parses and contains the name from the INDX chunk.
  * For data structures please refer to http://www.jmcgowan.com/odmlff2.pdf */
/* package */ class StreamIndexChunk implements AviChunk {

  public static final int TYPE_SUPER_INDEX_CHUNK = 1;
  public static final int TYPE_STANDARD_INDEX_CHUNK = 2;
  public static final int TYPE_FIELD_INDEX_CHUNK = 3;

  private static final String TAG = StreamIndexChunk.class.getName();

  /* bIndexType codes */
  private static final int AVI_INDEX_OF_INDEXES = 0x00;
  private static final int AVI_INDEX_OF_CHUNKS = 0x01;
  private static final int AVI_INDEX_IS_DATA =  0x80;

  /* bIndexSubtype codes for INDEX_OF_CHUNKS */
  private static final int AVI_INDEX_2FIELD = 0x01;

  /* One of the TYPE_SUPER_INDEX_CHUNK or TYPE_STANDARD_INDEX_CHUNK or TYPE_FIELD_INDEX_CHUNK */
  public int indexType;
  public int entrySize;
  public int chunkId;
  public long baseOffset;
  private ParsableByteArray body;
  private int entryRemains;

  public static StreamIndexChunk parseFrom(int trackType, ParsableByteArray body) {

    // Size of each entry in aIndex array (sizeof(aIndex[0])/sizeof(DWORD)):
    // AVI Standard Index Chunk is 2, AVI Field Index Chunk is 3, AVI_INDEX_OF_INDEXES is 4.
    short longsPerEntry = body.readLittleEndianShort();
    // bIndexSubType must be 0 or AVI_INDEX_2FIELD (0x01, fields within frames are also indexed)
    int indexSubType = body.readUnsignedByte();
    // One of AVI_INDEX_* codes: AVI_INDEX_OF_INDEXES(0x00) or AVI_INDEX_OF_CHUNKS(0x01)
    int indexType = body.readUnsignedByte();
    int entriesInUse = body.readLittleEndianInt();
    int chunkId = body.readLittleEndianInt();

    if (indexType == AVI_INDEX_OF_INDEXES) {

      // DWORD dwReserved[3]
      body.skipBytes(4 * 3);
      Assertions.checkState(longsPerEntry == 4);
      Assertions.checkState(indexSubType == 0);

      return new StreamIndexChunk(
          TYPE_SUPER_INDEX_CHUNK, chunkId, longsPerEntry, entriesInUse, body, 0);

    } else if (indexType == AVI_INDEX_OF_CHUNKS) {

      if (indexSubType == AVI_INDEX_2FIELD) {

        Assertions.checkState(longsPerEntry == 3);
        long qwBaseOffset = body.readLittleEndianLong();
        // DWORD dwReserved3
        body.skipBytes(4);

        return new StreamIndexChunk(
            TYPE_FIELD_INDEX_CHUNK, chunkId, longsPerEntry, entriesInUse, body, qwBaseOffset);

      } else {

        Assertions.checkState(longsPerEntry == 2);
        long qwBaseOffset = body.readLittleEndianLong();
        // DWORD dwReserved3
        body.skipBytes(4);

        return new StreamIndexChunk(
            TYPE_STANDARD_INDEX_CHUNK, chunkId, longsPerEntry, entriesInUse, body, qwBaseOffset);

      }
    } else if (indexType == AVI_INDEX_IS_DATA) {

      throw new IllegalArgumentException("Index Chunk not handled with AVI_INDEX_IS_DATA");
    }

    return null;
  }

  public StreamIndexChunk(
      int type, int chunkId, int perEntry, int entrySize, ParsableByteArray body, long baseOffset) {
    this.indexType = type;
    this.entrySize = entrySize;
    this.chunkId = chunkId;
    // Clone the body for future reading
    this.body = new ParsableByteArray(body.getData(), body.limit());
    this.body.setPosition(body.getPosition());
    body.skipBytes(perEntry * 4 * entrySize);
    this.baseOffset = baseOffset;
    this.entryRemains = entrySize;
  }

  @Override
  public int getType() {
    return AviExtractor.FOURCC_indx;
  }

  public boolean readSuperIndexEntry(SuperIndexEntry entry) {
    Assertions.checkState(indexType == TYPE_SUPER_INDEX_CHUNK);
    if (entryRemains > 0) {
      entryRemains--;
      entry.offset = body.readLittleEndianLong();
      entry.size = body.readLittleEndianInt();
      entry.duration = body.readLittleEndianInt();
      return true;
    }
    return false;
  }

  public boolean readStandardIndexEntry(StandardIndexEntry entry) {
    Assertions.checkState(indexType == TYPE_STANDARD_INDEX_CHUNK);
    if (entryRemains > 0) {
      entryRemains--;
      entry.offset = body.readLittleEndianInt();
      entry.size = body.readLittleEndianInt();
      return true;
    }
    return false;
  }

  public boolean readFieldIndexEntry(FieldIndexEntry entry) {
    Assertions.checkState(indexType == TYPE_FIELD_INDEX_CHUNK);
    if (entryRemains > 0) {
      entryRemains--;
      entry.offset = body.readLittleEndianInt();
      entry.size = body.readLittleEndianInt();
      entry.offsetField2 = body.readLittleEndianInt();
      return true;
    }
    return false;
  }

  public static class SuperIndexEntry {
    long offset;
    int size;
    int duration;
  }

  public static class StandardIndexEntry {
    int offset;
    int size;
  }

  public static class FieldIndexEntry {
    int offset;
    int size;
    int offsetField2;
  }
}
