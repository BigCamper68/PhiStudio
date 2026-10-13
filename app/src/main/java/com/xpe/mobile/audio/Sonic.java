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

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import static java.lang.Math.max;
import static java.lang.Math.min;

// Vendored from AndroidX Media 8c6678b657ede1e7883fc164ef73ed483c7796c3.
// Changes: package relocation, local assertion, and component extraction; no algorithm changes.



/**
 * Sonic audio stream processor for time/pitch stretching.
 *
 * <p>Based on https://github.com/waywardgeek/sonic.
 */
/* package */ final class Sonic {

  private static void checkState(boolean condition) {
    if (!condition) throw new IllegalStateException("Invalid Sonic state");
  }

  private static final int MINIMUM_PITCH = 65;
  private static final int MAXIMUM_PITCH = 400;
  private static final int AMDF_FREQUENCY = 4000;

  static final float MINIMUM_SPEEDUP_RATE = 1.00001f;
  static final float MINIMUM_SLOWDOWN_RATE = 0.99999f;

  private final int inputSampleRateHz;
  final int channelCount;
  private final float speed;
  private final float pitch;
  private final float rate;
  private final int minPeriod;
  private final int maxPeriod;
  final int maxRequiredFrameCount;
  private final SonicImpl<?> impl;

  int inputFrameCount;
  int outputFrameCount;
  int pitchFrameCount;
  int oldRatePosition;
  int newRatePosition;

  /**
   * Number of frames pending to be copied from {@link SonicImpl#getInputBuffer()} directly to
   * {@link SonicImpl#getOutputBuffer()}.
   *
   * <p>This field is only relevant to time-stretching or pitch-shifting in {@link
   * #changeSpeed(double)}, particularly when more frames need to be copied to the {@link
   * SonicImpl#getOutputBuffer()} than are available in {@link SonicImpl#getInputBuffer()} and Sonic
   * must wait until the next buffer (or EOS) is queued.
   */
  private int remainingInputToCopyFrameCount;

  int prevPeriod;
  private double accumulatedSpeedAdjustmentError;

  /**
   * Returns the estimated output frame count for a given configuration and input frame count.
   *
   * <p>Please note that the returned value might not be mathematically exact, as Sonic incurs in
   * truncation and precision errors that accumulate on the output.
   */
  public static long getExpectedFrameCountAfterProcessorApplied(
      int inputSampleRateHz,
      int outputSampleRateHz,
      float speed,
      float pitch,
      long inputFrameCount) {
        return SonicTiming.getExpectedFrameCountAfterProcessorApplied(inputSampleRateHz, outputSampleRateHz, speed, pitch, inputFrameCount);
    }

  /**
   * Returns expected accumulated truncation error for {@link Sonic}'s resampling algorithm, given
   * an input length, input sample rate, and resampling rate.
   *
   * <p><b>Note:</b> This method is only necessary until we address b/361768785 and fix the
   * underlying truncation issue.
   *
   * @param length Length of input in frames.
   * @param sampleRate Input sample rate of {@link Sonic} instance.
   * @param resamplingRate Resampling rate given by {@code pitch * (inputSampleRate /
   *     outputSampleRate)}.
   */
  /* package */ static long calculateAccumulatedTruncationErrorForResampling(
      BigDecimal length, BigDecimal sampleRate, BigDecimal resamplingRate) {
        return SonicTiming.calculateAccumulatedTruncationErrorForResampling(length, sampleRate, resamplingRate);
    }

  /**
   * Returns the number of input frames required for Sonic to produce the given number of output
   * frames under the specified parameters.
   *
   * <p>This method is the inverse of {@link #getExpectedFrameCountAfterProcessorApplied}.
   *
   * @param inputSampleRateHz Input sample rate in Hertz.
   * @param outputSampleRateHz Output sample rate in Hertz.
   * @param speed Speed rate.
   * @param pitch Pitch rate.
   * @param outputFrameCount Number of output frames to calculate the required input frame count of.
   */
  /* package */ static long getExpectedInputFrameCountForOutputFrameCount(
      int inputSampleRateHz,
      int outputSampleRateHz,
      float speed,
      float pitch,
      long outputFrameCount) {
        return SonicTiming.getExpectedInputFrameCountForOutputFrameCount(inputSampleRateHz, outputSampleRateHz, speed, pitch, outputFrameCount);
    }

  /**
   * Creates a new Sonic audio stream processor.
   *
   * @param inputSampleRateHz The sample rate of input audio, in hertz.
   * @param channelCount The number of channels in the input audio.
   * @param speed The speedup factor for output audio.
   * @param pitch The pitch factor for output audio.
   * @param outputSampleRateHz The sample rate for output audio, in hertz.
   * @param useFloatSamples Whether the input stream contains float PCM samples or 16 bit PCM
   *     samples.
   */
  public Sonic(
      int inputSampleRateHz,
      int channelCount,
      float speed,
      float pitch,
      int outputSampleRateHz,
      boolean useFloatSamples) {
    this.inputSampleRateHz = inputSampleRateHz;
    this.channelCount = channelCount;
    this.speed = speed;
    this.pitch = pitch;
    rate = (float) inputSampleRateHz / outputSampleRateHz;
    minPeriod = inputSampleRateHz / MAXIMUM_PITCH;
    maxPeriod = inputSampleRateHz / MINIMUM_PITCH;
    maxRequiredFrameCount = 2 * maxPeriod;
    impl = useFloatSamples ? new SonicFloatImpl(this) : new SonicShortImpl(this);
  }

  /**
   * Returns the number of bytes that have been input, but will not be processed until more input
   * data is provided.
   */
  public int getPendingInputBytes() {
    return inputFrameCount * channelCount * impl.bytesPerSample();
  }

  /**
   * Queues remaining data from {@code buffer}, and advances its position by the number of bytes
   * consumed.
   *
   * @param buffer A {@link ByteBuffer} containing input data between its position and limit.
   */
  public void queueInput(ByteBuffer buffer) {
    int bytesToWrite = buffer.remaining();
    int framesToWrite = bytesToWrite / (channelCount * impl.bytesPerSample());
    impl.ensureAdditionalFramesInInputBuffer(framesToWrite);
    impl.copyBufferToInputBuffer(buffer, bytesToWrite);
    inputFrameCount += framesToWrite;
    processStreamInput();
  }

  /**
   * Writes available output starting from the {@linkplain ByteBuffer#position() buffer's position}
   * until no more output is available or the buffer's limit is reached. The buffer's position will
   * be advanced by the number of bytes written.
   *
   * @param buffer A {@link ByteBuffer} into which output will be written.
   */
  public void getOutput(ByteBuffer buffer) {
    checkState(outputFrameCount >= 0);
    int framesToRead =
        min(buffer.remaining() / (channelCount * impl.bytesPerSample()), outputFrameCount);
    impl.copyOutputToByteBuffer(buffer, framesToRead);
    outputFrameCount -= framesToRead;
    System.arraycopy(
        impl.getOutputBuffer(),
        framesToRead * channelCount,
        impl.getOutputBuffer(),
        0,
        outputFrameCount * channelCount);
  }

  /**
   * Forces generating output using whatever data has been queued already. No extra delay will be
   * added to the output, but flushing in the middle of words could introduce distortion.
   */
  public void queueEndOfStream() {
    int remainingFrameCount = inputFrameCount;
    double s = speed / pitch;
    double r = rate * pitch;

    // If there are frames to be copied directly onto the output buffer, we should not count those
    // as "input frames" because Sonic is not applying any processing on them.
    int adjustedRemainingFrames = remainingFrameCount - remainingInputToCopyFrameCount;

    // We add directly to the output the number of frames in remainingInputToCopyFrameCount.
    // Otherwise, expectedOutputFrames will be off and will make Sonic output an incorrect number of
    // frames.
    int expectedOutputFrames =
        outputFrameCount
            + (int)
                ((adjustedRemainingFrames / s
                            + remainingInputToCopyFrameCount
                            + accumulatedSpeedAdjustmentError
                            + pitchFrameCount)
                        / r
                    + 0.5);
    accumulatedSpeedAdjustmentError = 0;

    // Add enough silence to flush both input and pitch buffers.
    impl.ensureAdditionalFramesInInputBuffer(remainingFrameCount + 2 * maxRequiredFrameCount);
    impl.zeroInputBuffer(remainingFrameCount * channelCount, 2 * maxRequiredFrameCount);
    inputFrameCount += 2 * maxRequiredFrameCount;
    processStreamInput();
    // Throw away any extra frames we generated due to the silence we added.
    if (outputFrameCount > expectedOutputFrames) {
      // expectedOutputFrames might be negative, so set lower bound to 0.
      outputFrameCount = max(expectedOutputFrames, 0);
    }
    // Empty input and pitch buffers.
    inputFrameCount = 0;
    remainingInputToCopyFrameCount = 0;
    pitchFrameCount = 0;
  }

  /** Clears state in preparation for receiving a new stream of input buffers. */
  public void flush() {
    inputFrameCount = 0;
    outputFrameCount = 0;
    pitchFrameCount = 0;
    oldRatePosition = 0;
    newRatePosition = 0;
    remainingInputToCopyFrameCount = 0;
    prevPeriod = 0;
    accumulatedSpeedAdjustmentError = 0;
    impl.flush();
  }

  /** Returns the size of output that can be read with {@link #getOutput(ByteBuffer)}, in bytes. */
  public int getOutputSize() {
    checkState(outputFrameCount >= 0);
    return outputFrameCount * channelCount * impl.bytesPerSample();
  }

  // Internal methods.

  private void copyToOutput(int positionFrames, int frameCount) {
    impl.ensureAdditionalFramesInOutputBuffer(frameCount);
    System.arraycopy(
        impl.getInputBuffer(),
        positionFrames * channelCount,
        impl.getOutputBuffer(),
        outputFrameCount * channelCount,
        frameCount * channelCount);
    outputFrameCount += frameCount;
  }

  private int copyInputToOutput(int positionFrames) {
    int frameCount = min(maxRequiredFrameCount, remainingInputToCopyFrameCount);
    copyToOutput(positionFrames, frameCount);
    remainingInputToCopyFrameCount -= frameCount;
    return frameCount;
  }

  private int findPitchPeriod(int positionFrames) {
    // Find the pitch period. This is a critical step, and we may have to try multiple ways to get a
    // good answer. This version uses AMDF. To improve speed, we down sample by an integer factor
    // get in the 11 kHz range, and then do it again with a narrower frequency range without down
    // sampling.
    int period;
    int retPeriod;
    int skip = inputSampleRateHz > AMDF_FREQUENCY ? inputSampleRateHz / AMDF_FREQUENCY : 1;
    if (channelCount == 1 && skip == 1) {
      period = impl.findPitchPeriodInRangeWithInputBuffer(positionFrames, minPeriod, maxPeriod);
    } else {
      impl.downSampleInput(positionFrames, skip);
      period =
          impl.findPitchPeriodInRangeWithDownsampleBuffer(0, minPeriod / skip, maxPeriod / skip);
      if (skip != 1) {
        period *= skip;
        int minP = period - (skip * 4);
        int maxP = period + (skip * 4);
        if (minP < minPeriod) {
          minP = minPeriod;
        }
        if (maxP > maxPeriod) {
          maxP = maxPeriod;
        }
        if (channelCount == 1) {
          period = impl.findPitchPeriodInRangeWithInputBuffer(positionFrames, minP, maxP);
        } else {
          impl.downSampleInput(positionFrames, 1);
          period = impl.findPitchPeriodInRangeWithDownsampleBuffer(0, minP, maxP);
        }
      }
    }
    if (impl.isPreviousPeriodBetter()) {
      retPeriod = prevPeriod;
    } else {
      retPeriod = period;
    }
    impl.updatePreviousMinDiff();
    prevPeriod = period;
    return retPeriod;
  }

  private void adjustRate(float rate, int originalOutputFrameCount) {
    if (outputFrameCount == originalOutputFrameCount) {
      return;
    }

    // Use long to avoid overflows int-int multiplications. The actual value of newSampleRate and
    // oldSampleRate should always be comfortably within the int range.
    long newSampleRate = (long) (inputSampleRateHz / rate);
    long oldSampleRate = inputSampleRateHz;
    // Set these values to help with the integer math.
    while (newSampleRate != 0
        && oldSampleRate != 0
        && newSampleRate % 2 == 0
        && oldSampleRate % 2 == 0) {
      newSampleRate /= 2;
      oldSampleRate /= 2;
    }
    moveNewSamplesToPitchBuffer(originalOutputFrameCount);
    // Leave at least one pitch sample in the buffer.
    for (int position = 0; position < pitchFrameCount - 1; position++) {
      // Cast to long to avoid overflow.
      while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
        impl.ensureAdditionalFramesInOutputBuffer(/* additionalFrameCount= */ 1);
        impl.interpolateFrame(position, oldSampleRate, newSampleRate);
        newRatePosition++;
        outputFrameCount++;
      }
      oldRatePosition++;
      if (oldRatePosition == oldSampleRate) {
        oldRatePosition = 0;
        checkState(newRatePosition == newSampleRate);
        newRatePosition = 0;
      }
    }
    removePitchFrames(pitchFrameCount - 1);
  }

  private void moveNewSamplesToPitchBuffer(int originalOutputFrameCount) {
    int frameCount = outputFrameCount - originalOutputFrameCount;
    impl.ensureAdditionalFramesInPitchBuffer(frameCount);
    System.arraycopy(
        impl.getOutputBuffer(),
        originalOutputFrameCount * channelCount,
        impl.getPitchBuffer(),
        pitchFrameCount * channelCount,
        frameCount * channelCount);
    outputFrameCount = originalOutputFrameCount;
    pitchFrameCount += frameCount;
  }

  private void removePitchFrames(int frameCount) {
    if (frameCount == 0) {
      return;
    }
    System.arraycopy(
        impl.getPitchBuffer(),
        frameCount * channelCount,
        impl.getPitchBuffer(),
        0,
        (pitchFrameCount - frameCount) * channelCount);
    pitchFrameCount -= frameCount;
  }

  private int skipPitchPeriod(int position, double speed, int period) {
    // Skip over a pitch period, and copy period/speed samples to the output.
    int newFrameCount;
    if (speed >= 2.0f) {
      double expectedFrameCount = period / (speed - 1.0) + accumulatedSpeedAdjustmentError;
      newFrameCount = (int) Math.round(expectedFrameCount);
      accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount;
    } else {
      newFrameCount = period;
      double expectedInputToCopy =
          period * (2.0f - speed) / (speed - 1.0f) + accumulatedSpeedAdjustmentError;
      remainingInputToCopyFrameCount = (int) Math.round(expectedInputToCopy);
      accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount;
    }
    impl.ensureAdditionalFramesInOutputBuffer(newFrameCount);
    impl.overlapAdd(newFrameCount, channelCount, outputFrameCount, position, position + period);
    outputFrameCount += newFrameCount;
    return newFrameCount;
  }

  private int insertPitchPeriod(int position, double speed, int period) {
    // Insert a pitch period, and determine how much input to copy directly.
    int newFrameCount;
    if (speed < 0.5f) {
      double expectedFrameCount = period * speed / (1.0f - speed) + accumulatedSpeedAdjustmentError;
      newFrameCount = (int) Math.round(expectedFrameCount);
      accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount;
    } else {
      newFrameCount = period;
      double expectedInputToCopy =
          period * (2.0f * speed - 1.0f) / (1.0f - speed) + accumulatedSpeedAdjustmentError;
      remainingInputToCopyFrameCount = (int) Math.round(expectedInputToCopy);
      accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount;
    }
    impl.ensureAdditionalFramesInOutputBuffer(period + newFrameCount);
    System.arraycopy(
        impl.getInputBuffer(),
        position * channelCount,
        impl.getOutputBuffer(),
        outputFrameCount * channelCount,
        period * channelCount);
    impl.overlapAdd(
        newFrameCount, channelCount, outputFrameCount + period, position + period, position);
    outputFrameCount += period + newFrameCount;
    return newFrameCount;
  }

  private void changeSpeed(double speed) {
    if (inputFrameCount < maxRequiredFrameCount) {
      return;
    }
    int frameCount = inputFrameCount;
    int positionFrames = 0;
    do {
      if (remainingInputToCopyFrameCount > 0) {
        positionFrames += copyInputToOutput(positionFrames);
      } else {
        int period = findPitchPeriod(positionFrames);
        if (speed > 1.0) {
          positionFrames += period + skipPitchPeriod(positionFrames, speed, period);
        } else {
          positionFrames += insertPitchPeriod(positionFrames, speed, period);
        }
      }
    } while (positionFrames + maxRequiredFrameCount <= frameCount);
    removeProcessedInputFrames(positionFrames);
  }

  private void removeProcessedInputFrames(int positionFrames) {
    int remainingFrames = inputFrameCount - positionFrames;
    System.arraycopy(
        impl.getInputBuffer(),
        positionFrames * channelCount,
        impl.getInputBuffer(),
        0,
        remainingFrames * channelCount);
    inputFrameCount = remainingFrames;
  }

  private void processStreamInput() {
    // Resample as many pitch periods as we have buffered on the input.
    int originalOutputFrameCount = outputFrameCount;
    double s = speed / pitch;
    float r = rate * pitch;
    if (s > MINIMUM_SPEEDUP_RATE || s < MINIMUM_SLOWDOWN_RATE) {
      changeSpeed(s);
    } else {
      copyToOutput(0, inputFrameCount);
      inputFrameCount = 0;
    }
    if (r != 1.0f) {
      adjustRate(r, originalOutputFrameCount);
    }
  }


}
