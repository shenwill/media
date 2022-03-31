/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static com.google.common.collect.Iterables.getLast;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * {@code FrameProcessorChain} applies changes to individual video frames.
 *
 * <p>Input becomes available on its {@linkplain #getInputSurface() input surface} asynchronously
 * and is processed on a background thread as it becomes available. All input frames should be
 * {@linkplain #registerInputFrame() registered} before they are rendered to the input surface.
 * {@link #getPendingFrameCount()} can be used to check whether there are frames that have not been
 * fully processed yet. Output is written to its {@linkplain #configure(Surface, int, int,
 * SurfaceView) output surface}.
 */
/* package */ final class FrameProcessorChain {

  static {
    GlUtil.glAssertionsEnabled = true;
  }

  private static final String THREAD_NAME = "Transformer:FrameProcessorChain";

  private final boolean enableExperimentalHdrEditing;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  /** Some OpenGL commands may block, so all OpenGL commands are run on a background thread. */
  private final ExecutorService singleThreadExecutorService;
  /** Futures corresponding to the executor service's pending tasks. */
  private final ConcurrentLinkedQueue<Future<?>> futures;
  /** Number of frames {@linkplain #registerInputFrame() registered} but not fully processed. */
  private final AtomicInteger pendingFrameCount;
  /** Prevents further frame processing tasks from being scheduled after {@link #release()}. */
  private volatile boolean releaseRequested;

  private boolean inputStreamEnded;
  /** Wraps the {@link #inputSurfaceTexture}. */
  private @MonotonicNonNull Surface inputSurface;
  /** Associated with an OpenGL external texture. */
  private @MonotonicNonNull SurfaceTexture inputSurfaceTexture;
  /**
   * Identifier of the external texture the {@link ExternalCopyFrameProcessor} reads its input from.
   */
  private int inputExternalTexId;
  /** Transformation matrix associated with the {@link #inputSurfaceTexture}. */
  private final float[] textureTransformMatrix;

  private final ExternalCopyFrameProcessor externalCopyFrameProcessor;
  private final ImmutableList<GlFrameProcessor> frameProcessors;
  /**
   * Identifiers of a framebuffer object associated with the intermediate textures that receive
   * output from the previous {@link GlFrameProcessor}, and provide input for the following {@link
   * GlFrameProcessor}.
   *
   * <p>The {@link ExternalCopyFrameProcessor} writes to the first framebuffer.
   */
  private final int[] framebuffers;
  /** The input {@link Size} of each of the {@code frameProcessors}. */
  private final ImmutableList<Size> inputSizes;

  private int outputWidth;
  private int outputHeight;
  /**
   * Wraps the output {@link Surface} that is populated with the output of the final {@link
   * GlFrameProcessor} for each frame.
   */
  private @MonotonicNonNull EGLSurface eglSurface;

  private int debugPreviewWidth;
  private int debugPreviewHeight;
  /**
   * Wraps a debug {@link SurfaceView} that is populated with the output of the final {@link
   * GlFrameProcessor} for each frame.
   */
  private @MonotonicNonNull EGLSurface debugPreviewEglSurface;

  /**
   * Creates a new instance.
   *
   * @param context A {@link Context}.
   * @param pixelWidthHeightRatio The ratio of width over height, for each pixel.
   * @param inputWidth The input frame width, in pixels.
   * @param inputHeight The input frame height, in pixels.
   * @param frameProcessors The {@link GlFrameProcessor GlFrameProcessors} to apply to each frame.
   * @param enableExperimentalHdrEditing Whether to attempt to process the input as an HDR signal.
   * @throws TransformationException If the {@code pixelWidthHeightRatio} isn't 1.
   */
  public FrameProcessorChain(
      Context context,
      float pixelWidthHeightRatio,
      int inputWidth,
      int inputHeight,
      List<GlFrameProcessor> frameProcessors,
      boolean enableExperimentalHdrEditing)
      throws TransformationException {
    if (pixelWidthHeightRatio != 1.0f) {
      // TODO(b/211782176): Consider implementing support for non-square pixels.
      throw TransformationException.createForFrameProcessorChain(
          new UnsupportedOperationException(
              "Transformer's FrameProcessorChain currently does not support frame edits on"
                  + " non-square pixels. The pixelWidthHeightRatio is: "
                  + pixelWidthHeightRatio),
          TransformationException.ERROR_CODE_GL_INIT_FAILED);
    }

    this.enableExperimentalHdrEditing = enableExperimentalHdrEditing;
    this.frameProcessors = ImmutableList.copyOf(frameProcessors);

    singleThreadExecutorService = Util.newSingleThreadExecutor(THREAD_NAME);
    futures = new ConcurrentLinkedQueue<>();
    pendingFrameCount = new AtomicInteger();
    textureTransformMatrix = new float[16];
    externalCopyFrameProcessor =
        new ExternalCopyFrameProcessor(context, enableExperimentalHdrEditing);
    framebuffers = new int[frameProcessors.size()];
    Pair<ImmutableList<Size>, Size> sizes =
        configureFrameProcessorSizes(inputWidth, inputHeight, frameProcessors);
    inputSizes = sizes.first;
    outputWidth = sizes.second.getWidth();
    outputHeight = sizes.second.getHeight();
    debugPreviewWidth = C.LENGTH_UNSET;
    debugPreviewHeight = C.LENGTH_UNSET;
  }

  /** Returns the output {@link Size}. */
  public Size getOutputSize() {
    return new Size(outputWidth, outputHeight);
  }

  /**
   * Configures the {@code FrameProcessorChain} to process frames to the specified output targets.
   *
   * <p>This method may only be called once and may override the {@linkplain
   * GlFrameProcessor#configureOutputSize(int, int) output size} of the final {@link
   * GlFrameProcessor}.
   *
   * @param outputSurface The output {@link Surface}.
   * @param outputWidth The output width, in pixels.
   * @param outputHeight The output height, in pixels.
   * @param debugSurfaceView Optional debug {@link SurfaceView} to show output.
   * @throws IllegalStateException If the {@code FrameProcessorChain} has already been configured.
   * @throws TransformationException If reading shader files fails, or an OpenGL error occurs while
   *     creating and configuring the OpenGL components.
   */
  public void configure(
      Surface outputSurface,
      int outputWidth,
      int outputHeight,
      @Nullable SurfaceView debugSurfaceView)
      throws TransformationException {
    checkState(inputSurface == null, "The FrameProcessorChain has already been configured.");
    // TODO(b/218488308): Don't override output size for encoder fallback. Instead allow the final
    //  GlFrameProcessor to be re-configured or append another GlFrameProcessor.
    this.outputWidth = outputWidth;
    this.outputHeight = outputHeight;

    if (debugSurfaceView != null) {
      debugPreviewWidth = debugSurfaceView.getWidth();
      debugPreviewHeight = debugSurfaceView.getHeight();
    }

    try {
      // Wait for task to finish to be able to use inputExternalTexId to create the SurfaceTexture.
      singleThreadExecutorService
          .submit(this::createOpenGlObjectsAndInitializeFrameProcessors)
          .get();
    } catch (ExecutionException e) {
      throw TransformationException.createForFrameProcessorChain(
          e, TransformationException.ERROR_CODE_GL_INIT_FAILED);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw TransformationException.createForFrameProcessorChain(
          e, TransformationException.ERROR_CODE_GL_INIT_FAILED);
    }

    inputSurfaceTexture = new SurfaceTexture(inputExternalTexId);
    inputSurfaceTexture.setOnFrameAvailableListener(
        surfaceTexture -> {
          if (releaseRequested) {
            // Frames can still become available after a transformation is cancelled but they can be
            // ignored.
            return;
          }
          try {
            futures.add(singleThreadExecutorService.submit(this::processFrame));
          } catch (RejectedExecutionException e) {
            if (!releaseRequested) {
              throw e;
            }
          }
        });
    inputSurface = new Surface(inputSurfaceTexture);

    futures.add(
        singleThreadExecutorService.submit(
            () -> createOpenGlSurfaces(outputSurface, debugSurfaceView)));
  }

  /**
   * Returns the input {@link Surface}.
   *
   * <p>The {@code FrameProcessorChain} must be {@linkplain #configure(Surface, int, int,
   * SurfaceView) configured}.
   */
  public Surface getInputSurface() {
    checkStateNotNull(inputSurface, "The FrameProcessorChain must be configured.");
    return inputSurface;
  }

  /**
   * Informs the {@code FrameProcessorChain} that a frame will be queued to its input surface.
   *
   * <p>Should be called before rendering a frame to the frame processor chain's input surface.
   *
   * @throws IllegalStateException If called after {@link #signalEndOfInputStream()}.
   */
  public void registerInputFrame() {
    checkState(!inputStreamEnded);
    pendingFrameCount.incrementAndGet();
  }

  /**
   * Checks whether any exceptions occurred during asynchronous frame processing and rethrows the
   * first exception encountered.
   */
  public void getAndRethrowBackgroundExceptions() throws TransformationException {
    @Nullable Future<?> oldestGlProcessingFuture = futures.peek();
    while (oldestGlProcessingFuture != null && oldestGlProcessingFuture.isDone()) {
      futures.poll();
      try {
        oldestGlProcessingFuture.get();
      } catch (ExecutionException e) {
        throw TransformationException.createForFrameProcessorChain(
            e, TransformationException.ERROR_CODE_GL_PROCESSING_FAILED);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw TransformationException.createForFrameProcessorChain(
            e, TransformationException.ERROR_CODE_GL_PROCESSING_FAILED);
      }
      oldestGlProcessingFuture = futures.peek();
    }
  }

  /**
   * Returns the number of input frames that have been {@linkplain #registerInputFrame() registered}
   * but not completely processed yet.
   */
  public int getPendingFrameCount() {
    return pendingFrameCount.get();
  }

  /** Returns whether all frames have been processed. */
  public boolean isEnded() {
    return inputStreamEnded && getPendingFrameCount() == 0;
  }

  /** Informs the {@code FrameProcessorChain} that no further input frames should be accepted. */
  public void signalEndOfInputStream() {
    inputStreamEnded = true;
  }

  /**
   * Releases all resources.
   *
   * <p>If the frame processor chain is released before it has {@linkplain #isEnded() ended}, it
   * will attempt to cancel processing any input frames that have already become available. Input
   * frames that become available after release are ignored.
   */
  public void release() {
    releaseRequested = true;
    while (!futures.isEmpty()) {
      checkNotNull(futures.poll()).cancel(/* mayInterruptIfRunning= */ true);
    }
    futures.add(
        singleThreadExecutorService.submit(
            () -> {
              externalCopyFrameProcessor.release();
              for (int i = 0; i < frameProcessors.size(); i++) {
                frameProcessors.get(i).release();
              }
              GlUtil.destroyEglContext(eglDisplay, eglContext);
            }));
    if (inputSurfaceTexture != null) {
      inputSurfaceTexture.release();
    }
    if (inputSurface != null) {
      inputSurface.release();
    }
    singleThreadExecutorService.shutdown();
  }

  /**
   * Creates the OpenGL surfaces.
   *
   * <p>This method should only be called after {@link
   * #createOpenGlObjectsAndInitializeFrameProcessors()} and must be called on the background
   * thread.
   */
  private void createOpenGlSurfaces(Surface outputSurface, @Nullable SurfaceView debugSurfaceView) {
    checkState(Thread.currentThread().getName().equals(THREAD_NAME));
    checkStateNotNull(eglDisplay);

    if (enableExperimentalHdrEditing) {
      // TODO(b/209404935): Don't assume BT.2020 PQ input/output.
      eglSurface = GlUtil.getEglSurfaceBt2020Pq(eglDisplay, outputSurface);
      if (debugSurfaceView != null) {
        debugPreviewEglSurface =
            GlUtil.getEglSurfaceBt2020Pq(eglDisplay, checkNotNull(debugSurfaceView.getHolder()));
      }
    } else {
      eglSurface = GlUtil.getEglSurface(eglDisplay, outputSurface);
      if (debugSurfaceView != null) {
        debugPreviewEglSurface =
            GlUtil.getEglSurface(eglDisplay, checkNotNull(debugSurfaceView.getHolder()));
      }
    }
  }

  /**
   * Creates the OpenGL textures and framebuffers, and initializes the {@link GlFrameProcessor
   * GlFrameProcessors}.
   *
   * <p>This method should only be called on the background thread.
   */
  private Void createOpenGlObjectsAndInitializeFrameProcessors() throws IOException {
    checkState(Thread.currentThread().getName().equals(THREAD_NAME));

    eglDisplay = GlUtil.createEglDisplay();
    eglContext =
        enableExperimentalHdrEditing
            ? GlUtil.createEglContextEs3Rgba1010102(eglDisplay)
            : GlUtil.createEglContext(eglDisplay);

    if (GlUtil.isSurfacelessContextExtensionSupported()) {
      GlUtil.focusEglSurface(
          eglDisplay, eglContext, EGL14.EGL_NO_SURFACE, /* width= */ 1, /* height= */ 1);
    } else if (enableExperimentalHdrEditing) {
      // TODO(b/209404935): Don't assume BT.2020 PQ input/output.
      GlUtil.focusPlaceholderEglSurfaceBt2020Pq(eglContext, eglDisplay);
    } else {
      GlUtil.focusPlaceholderEglSurface(eglContext, eglDisplay);
    }

    inputExternalTexId = GlUtil.createExternalTexture();
    Size inputSize = inputSizes.get(0);
    externalCopyFrameProcessor.configureOutputSize(inputSize.getWidth(), inputSize.getHeight());
    externalCopyFrameProcessor.initialize(inputExternalTexId);

    for (int i = 0; i < frameProcessors.size(); i++) {
      inputSize = inputSizes.get(i);
      int inputTexId = GlUtil.createTexture(inputSize.getWidth(), inputSize.getHeight());
      framebuffers[i] = GlUtil.createFboForTexture(inputTexId);
      frameProcessors.get(i).initialize(inputTexId);
    }
    // Return something because only Callables not Runnables can throw checked exceptions.
    return null;
  }

  /**
   * Processes an input frame.
   *
   * <p>This method should only be called on the background thread.
   */
  @RequiresNonNull("inputSurfaceTexture")
  private void processFrame() {
    checkState(Thread.currentThread().getName().equals(THREAD_NAME));
    checkStateNotNull(eglSurface);
    checkStateNotNull(eglContext);
    checkStateNotNull(eglDisplay);

    if (frameProcessors.isEmpty()) {
      GlUtil.focusEglSurface(eglDisplay, eglContext, eglSurface, outputWidth, outputHeight);
    } else {
      GlUtil.focusFramebuffer(
          eglDisplay,
          eglContext,
          eglSurface,
          framebuffers[0],
          inputSizes.get(0).getWidth(),
          inputSizes.get(0).getHeight());
    }
    inputSurfaceTexture.updateTexImage();
    inputSurfaceTexture.getTransformMatrix(textureTransformMatrix);
    externalCopyFrameProcessor.setTextureTransformMatrix(textureTransformMatrix);
    long presentationTimeNs = inputSurfaceTexture.getTimestamp();
    long presentationTimeUs = presentationTimeNs / 1000;
    clearOutputFrame();
    externalCopyFrameProcessor.updateProgramAndDraw(presentationTimeUs);

    for (int i = 0; i < frameProcessors.size() - 1; i++) {
      Size outputSize = inputSizes.get(i + 1);
      GlUtil.focusFramebuffer(
          eglDisplay,
          eglContext,
          eglSurface,
          framebuffers[i + 1],
          outputSize.getWidth(),
          outputSize.getHeight());
      clearOutputFrame();
      frameProcessors.get(i).updateProgramAndDraw(presentationTimeUs);
    }
    if (!frameProcessors.isEmpty()) {
      GlUtil.focusEglSurface(eglDisplay, eglContext, eglSurface, outputWidth, outputHeight);
      clearOutputFrame();
      getLast(frameProcessors).updateProgramAndDraw(presentationTimeUs);
    }

    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs);
    EGL14.eglSwapBuffers(eglDisplay, eglSurface);

    if (debugPreviewEglSurface != null) {
      GlUtil.focusEglSurface(
          eglDisplay, eglContext, debugPreviewEglSurface, debugPreviewWidth, debugPreviewHeight);
      clearOutputFrame();
      // The four-vertex triangle strip forms a quad.
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      EGL14.eglSwapBuffers(eglDisplay, debugPreviewEglSurface);
    }

    checkState(pendingFrameCount.getAndDecrement() > 0);
  }

  private static void clearOutputFrame() {
    GLES20.glClearColor(/* red= */ 0, /* green= */ 0, /* blue= */ 0, /* alpha= */ 0);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GlUtil.checkGlError();
  }

  /**
   * Configures the input and output {@linkplain Size sizes} of a list of {@link GlFrameProcessor
   * GlFrameProcessors}.
   *
   * @param inputWidth The width of frames passed to the first {@link GlFrameProcessor}, in pixels.
   * @param inputHeight The height of frames passed to the first {@link GlFrameProcessor}, in
   *     pixels.
   * @param frameProcessors The {@link GlFrameProcessor GlFrameProcessors}.
   * @return The input {@link Size} of each {@link GlFrameProcessor} and the output {@link Size} of
   *     the final {@link GlFrameProcessor}.
   */
  private static Pair<ImmutableList<Size>, Size> configureFrameProcessorSizes(
      int inputWidth, int inputHeight, List<GlFrameProcessor> frameProcessors) {
    Size size = new Size(inputWidth, inputHeight);
    if (frameProcessors.isEmpty()) {
      return Pair.create(ImmutableList.of(size), size);
    }

    ImmutableList.Builder<Size> inputSizes = new ImmutableList.Builder<>();
    for (int i = 0; i < frameProcessors.size(); i++) {
      inputSizes.add(size);
      size = frameProcessors.get(i).configureOutputSize(size.getWidth(), size.getHeight());
    }
    return Pair.create(inputSizes.build(), size);
  }
}
