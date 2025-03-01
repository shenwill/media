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

import static androidx.media3.extractor.ape.ApeUtil.getSamplesAtTimeUs;
import static androidx.media3.extractor.ape.ApeUtil.getTimeUs;

import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;

public final class ApeSeekTableSeekMap implements SeekMap {

  private final ApeInfo apeInfo;
  private final long[] frameSamplesAddUp;
  private final long[] framePositions;

  public ApeSeekTableSeekMap(ApeInfo info, long[] framePositions, long[] frameSamplesAddUp) {
    this.apeInfo = info;
    this.framePositions = framePositions;
    this.frameSamplesAddUp = frameSamplesAddUp;
  }

  @Override
  public boolean isSeekable() {
    return true;
  }

  @Override
  public long getDurationUs() {
    return apeInfo.durationUs;
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    Assertions.checkStateNotNull(frameSamplesAddUp);

    long targetSampleNumber = getSamplesAtTimeUs(timeUs, apeInfo.sampleRate, apeInfo.totalSamples);
    int index =
        Util.binarySearchFloor(
            frameSamplesAddUp,
            targetSampleNumber,
            /* inclusive= */ true,
            /* stayInBounds= */ true);

    long seekPointSampleNumber = frameSamplesAddUp[index];
    long position = framePositions[index];
    SeekPoint seekPoint = getSeekPoint(seekPointSampleNumber, position);
    if (seekPoint.timeUs == timeUs || index == frameSamplesAddUp.length - 1) {
      return new SeekPoints(seekPoint);
    } else {
      SeekPoint secondSeekPoint =
          getSeekPoint(frameSamplesAddUp[index + 1], framePositions[index + 1]);
      return new SeekPoints(seekPoint, secondSeekPoint);
    }
  }

  private SeekPoint getSeekPoint(long sampleNumber, long position) {
    long seekTimeUs = getTimeUs(sampleNumber, apeInfo.sampleRate);
    return new SeekPoint(seekTimeUs, position);
  }
}
