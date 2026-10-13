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

interface SonicImpl<T extends Object> {

/** Returns the number of bytes in a sample. */
int bytesPerSample();

/**
 * Interpolates two contiguous frames in the {@linkplain #getPitchBuffer() pitch buffer} at
 * {@code positionFrames} and writes the result to the {@linkplain #getOutputBuffer() output
 * buffer}.
 *
 * @param positionFrames The position in frames of the first frame to interpolate from the pitch
 *     buffer.
 * @param oldSampleRate The old sample rate.
 * @param newSampleRate The new sample rate.
 */
void interpolateFrame(int positionFrames, long oldSampleRate, long newSampleRate);

/**
 * Returns whether the previous pitch period estimate is a better approximation, which can occur
 * at the abrupt end of voiced words.
 */
boolean isPreviousPeriodBetter();

/**
 * Downsamples {@link #maxRequiredFrameCount} / {@code skip} frames from the input buffer onto
 * the downsampling buffer.
 *
 * @param positionFrames The position in frames from which to start downsampling the input
 *     buffer.
 * @param skip The number of frames to skip per downsampled frame.
 */
void downSampleInput(int positionFrames, int skip);

/**
 * Returns the pitch period within {@code [minPeriod; maxPeriod]} at {@code positionFrames} of
 * the downsample buffer.
 */
int findPitchPeriodInRangeWithDownsampleBuffer(
    int positionFrames, int minPeriod, int maxPeriod);

/**
 * Returns the pitch period within {@code [minPeriod; maxPeriod]} at {@code positionFrames} of
 * the input buffer.
 */
int findPitchPeriodInRangeWithInputBuffer(int positionFrames, int minPeriod, int maxPeriod);

/** Clears state in preparation for receiving a new stream of input buffers. */
void flush();

/**
 * Overlap-adds {@code frameCount} frames to the output buffer from {@code rampDownPosition} and
 * {@code rampUpPosition} in the input buffer.
 *
 * @param frameCount The number of frames to overlap-add.
 * @param channelCount The number of channels in a frame.
 * @param outPosition The starting position in the output buffer to write to.
 * @param rampDownPosition The starting position in the input buffer to ramp down.
 * @param rampUpPosition The starting position in the input buffer to ramp up.
 */
void overlapAdd(
    int frameCount,
    int channelCount,
    int outPosition,
    int rampDownPosition,
    int rampUpPosition);

/**
 * Updates the previous minimum diff with the current diff.
 *
 * @see #findPitchPeriod(int)
 */
void updatePreviousMinDiff();

/**
 * Adds {@code additionalFrameCount} frames to the input buffer if there are less than {@code
 * additionalFrameCount} available frames.
 */
void ensureAdditionalFramesInInputBuffer(int additionalFrameCount);

/**
 * Adds {@code additionalFrameCount} frames to the output buffer if there are less than {@code
 * additionalFrameCount} available frames.
 */
void ensureAdditionalFramesInOutputBuffer(int additionalFrameCount);

/**
 * Adds {@code additionalFrameCount} frames to the pitch buffer if there are less than {@code
 * additionalFrameCount} available frames.
 */
void ensureAdditionalFramesInPitchBuffer(int additionalFrameCount);

/** Zeroes the input buffer from {@code startPosition} to {@code startPosition + length}. */
void zeroInputBuffer(int startPosition, int length);

/** Copies {@code bytesToWrite} bytes from {@code buffer} onto the input buffer. */
void copyBufferToInputBuffer(ByteBuffer buffer, int bytesToWrite);

/** Copies {@code framesToRead} frames from the output buffer onto {@code buffer}. */
void copyOutputToByteBuffer(ByteBuffer buffer, int framesToRead);

/** Returns the input buffer. */
T getInputBuffer();

/** Returns the output buffer. */
T getOutputBuffer();

/** Returns the pitch buffer. */
T getPitchBuffer();
  }
