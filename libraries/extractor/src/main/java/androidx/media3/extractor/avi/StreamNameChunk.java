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

import androidx.media3.common.util.ParsableByteArray;

/** Parses and contains the name from the STRN chunk. */
/* package */ final class StreamNameChunk implements AviChunk {

  public static StreamNameChunk parseFrom(ParsableByteArray body) {
    byte[] bytes = new byte[body.bytesLeft()];
    body.readBytes(bytes, 0 , bytes.length);
    int len = bytes.length;
    if (bytes[bytes.length - 1] == 0) {
      len -= 1;
    }
    String name = new String(bytes, 0 , len);
    return new StreamNameChunk(name);
  }

  public final String name;

  private StreamNameChunk(String name) {
    this.name = name;
  }

  @Override
  public int getType() {
    return AviExtractor.FOURCC_strn;
  }
}
