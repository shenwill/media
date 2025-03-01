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
import androidx.media3.common.util.Util;

public class ApeUtil {

  public static long getSamplesAtTimeUs(long timeUs, long sampleRate, long totalSamples) {
    long sampleNumber = (timeUs * sampleRate) / C.MICROS_PER_SECOND;
    return Util.constrainValue(sampleNumber, 0, totalSamples - 1);
  }

  public static long getTimeUs(long samples, long sampleRate) {
    return C.MICROS_PER_SECOND * samples / sampleRate;
  }
}
