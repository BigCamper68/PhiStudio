/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2010 Bill Cox, Sonic Library
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
package com.xpe.mobile.audio;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** SonicShortImpl view extracted from Sonic. */
final class SonicShortImpl implements SonicImpl<short[]> {
    private final Sonic delegateOwner;

private final short[] downSampleBuffer;

private short[] inputBuffer;
private short[] outputBuffer;
private short[] pitchBuffer;

private int minDiff;
private int maxDiff;
private int prevMinDiff;

SonicShortImpl(Sonic delegateOwner) {
  this.delegateOwner = delegateOwner;
  downSampleBuffer = new short[delegateOwner.maxRequiredFrameCount];
  inputBuffer = new short[delegateOwner.maxRequiredFrameCount * delegateOwner.channelCount];
  outputBuffer = new short[delegateOwner.maxRequiredFrameCount * delegateOwner.channelCount];
  pitchBuffer = new short[delegateOwner.maxRequiredFrameCount * delegateOwner.channelCount];
}

@Override
public int bytesPerSample() {
  return 2;
}

@Override
public void interpolateFrame(int positionFrames, long oldSampleRate, long newSampleRate) {
  for (int i = 0; i < delegateOwner.channelCount; i++) {
    outputBuffer[delegateOwner.outputFrameCount * delegateOwner.channelCount + i] =
        interpolate(
            pitchBuffer, positionFrames * delegateOwner.channelCount + i, oldSampleRate, newSampleRate);
  }
}

@Override
public boolean isPreviousPeriodBetter() {
  if (minDiff == 0 || delegateOwner.prevPeriod == 0) {
    return false;
  }
  if (maxDiff > minDiff * 3) {
    // Got a reasonable match this period.
    return false;
  }
  if (minDiff * 2 <= prevMinDiff * 3) {
    // Mismatch is not that much greater this period.
    return false;
  }
  return true;
}

@Override
public void downSampleInput(int positionFrames, int skip) {
  short[] samples = inputBuffer;
  // If skip is greater than one, average skip samples together and write them to the
  // down-sample
  // buffer. If channelCount is greater than one, mix the channels together as we down sample.
  int frameCount = delegateOwner.maxRequiredFrameCount / skip;
  int samplesPerValue = delegateOwner.channelCount * skip;
  positionFrames *= delegateOwner.channelCount;
  for (int i = 0; i < frameCount; i++) {
    int value = 0;
    for (int j = 0; j < samplesPerValue; j++) {
      value += samples[positionFrames + i * samplesPerValue + j];
    }
    value /= samplesPerValue;
    downSampleBuffer[i] = (short) value;
  }
}

@Override
public int findPitchPeriodInRangeWithDownsampleBuffer(
    int positionFrames, int minPeriod, int maxPeriod) {
  return findPitchPeriodInRange(downSampleBuffer, positionFrames, minPeriod, maxPeriod);
}

@Override
public int findPitchPeriodInRangeWithInputBuffer(
    int positionFrames, int minPeriod, int maxPeriod) {
  return findPitchPeriodInRange(inputBuffer, positionFrames, minPeriod, maxPeriod);
}

@Override
public void flush() {
  prevMinDiff = 0;
  minDiff = 0;
  maxDiff = 0;
}

@Override
public void overlapAdd(
    int frameCount,
    int channelCount,
    int outPosition,
    int rampDownPosition,
    int rampUpPosition) {
  overlapAdd(
      frameCount,
      channelCount,
      outputBuffer,
      outPosition,
      inputBuffer,
      rampDownPosition,
      inputBuffer,
      rampUpPosition);
}

private void overlapAdd(
    int frameCount,
    int channelCount,
    short[] out,
    int outPosition,
    short[] rampDown,
    int rampDownPosition,
    short[] rampUp,
    int rampUpPosition) {
  for (int i = 0; i < channelCount; i++) {
    int o = outPosition * channelCount + i;
    int u = rampUpPosition * channelCount + i;
    int d = rampDownPosition * channelCount + i;
    for (int t = 0; t < frameCount; t++) {
      out[o] = (short) ((rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount);
      o += channelCount;
      d += channelCount;
      u += channelCount;
    }
  }
}

@Override
public void updatePreviousMinDiff() {
  prevMinDiff = minDiff;
}

@Override
public void ensureAdditionalFramesInInputBuffer(int additionalFrameCount) {
  inputBuffer =
      ensureSpaceForAdditionalFrames(inputBuffer, delegateOwner.inputFrameCount, additionalFrameCount);
}

@Override
public void ensureAdditionalFramesInOutputBuffer(int additionalFrameCount) {
  outputBuffer =
      ensureSpaceForAdditionalFrames(outputBuffer, delegateOwner.outputFrameCount, additionalFrameCount);
}

@Override
public void ensureAdditionalFramesInPitchBuffer(int additionalFrameCount) {
  pitchBuffer =
      ensureSpaceForAdditionalFrames(pitchBuffer, delegateOwner.pitchFrameCount, additionalFrameCount);
}

@Override
public void zeroInputBuffer(int startPosition, int length) {
  for (int i = 0; i < length * delegateOwner.channelCount; i++) {
    inputBuffer[startPosition + i] = 0;
  }
}

@Override
public void copyBufferToInputBuffer(ByteBuffer buffer, int bytesToWrite) {
  buffer.asShortBuffer().get(inputBuffer, delegateOwner.inputFrameCount * delegateOwner.channelCount, bytesToWrite / 2);
  buffer.position(buffer.position() + bytesToWrite);
}

@Override
public void copyOutputToByteBuffer(ByteBuffer buffer, int framesToRead) {
  buffer.asShortBuffer().put(outputBuffer, 0, framesToRead * delegateOwner.channelCount);
  buffer.position(buffer.position() + framesToRead * bytesPerSample() * delegateOwner.channelCount);
}

@Override
public short[] getInputBuffer() {
  return inputBuffer;
}

@Override
public short[] getOutputBuffer() {
  return outputBuffer;
}

@Override
public short[] getPitchBuffer() {
  return pitchBuffer;
}

private int findPitchPeriodInRange(
    short[] samples, int positionFrames, int minPeriod, int maxPeriod) {
  // Find the best frequency match in the range, and given a sample skip multiple. For now, just
  // find the pitch of the first channel.
  int bestPeriod = 0;
  int worstPeriod = 255;
  int minDiff = 1;
  int maxDiff = 0;
  positionFrames *= delegateOwner.channelCount;
  for (int period = minPeriod; period <= maxPeriod; period++) {
    int diff = 0;
    for (int i = 0; i < period; i++) {
      short sVal = samples[positionFrames + i];
      short pVal = samples[positionFrames + period + i];
      diff += Math.abs(sVal - pVal);
    }
    // Note that the highest number of samples we add into diff will be less than 256, since we
    // skip samples. Thus, diff is a 24 bit number, and we can safely multiply by numSamples
    // without overflow.
    if (diff * bestPeriod < minDiff * period) {
      minDiff = diff;
      bestPeriod = period;
    }
    if (diff * worstPeriod > maxDiff * period) {
      maxDiff = diff;
      worstPeriod = period;
    }
  }
  this.minDiff = minDiff / bestPeriod;
  this.maxDiff = maxDiff / worstPeriod;
  return bestPeriod;
}

private short interpolate(short[] in, int inPos, long oldSampleRate, long newSampleRate) {
  short left = in[inPos];
  short right = in[inPos + delegateOwner.channelCount];
  long position = delegateOwner.newRatePosition * oldSampleRate;
  long leftPosition = delegateOwner.oldRatePosition * newSampleRate;
  long rightPosition = (delegateOwner.oldRatePosition + 1) * newSampleRate;
  long ratio = rightPosition - position;
  long width = rightPosition - leftPosition;
  return (short) ((ratio * left + (width - ratio) * right) / width);
}

/**
 * Returns {@code buffer} or a copy of it, such that there is enough space in the returned
 * buffer to store {@code newFrameCount} additional frames.
 *
 * @param buffer The buffer.
 * @param frameCount The number of frames already in the buffer.
 * @param additionalFrameCount The number of additional frames that need to be stored in the
 *     buffer.
 * @return A buffer with enough space for the additional frames.
 */
private short[] ensureSpaceForAdditionalFrames(
    short[] buffer, int frameCount, int additionalFrameCount) {
  int currentCapacityFrames = buffer.length / delegateOwner.channelCount;
  if (frameCount + additionalFrameCount <= currentCapacityFrames) {
    return buffer;
  } else {
    int newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount;
    return Arrays.copyOf(buffer, newCapacityFrames * delegateOwner.channelCount);
  }
}
  }
