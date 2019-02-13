/*
 * Copyright (C) 2019 The Android Open Source Project
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

package androidx.camera.view;

import android.Manifest.permission;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.util.Size;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.annotation.UiThread;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraOrientationUtil;
import androidx.camera.core.CameraX;
import androidx.camera.core.CameraX.LensFacing;
import androidx.camera.core.FlashMode;
import androidx.camera.core.ImageCaptureUseCase;
import androidx.camera.core.ImageCaptureUseCase.OnImageCapturedListener;
import androidx.camera.core.ImageCaptureUseCase.OnImageSavedListener;
import androidx.camera.core.ImageCaptureUseCaseConfiguration;
import androidx.camera.core.VideoCaptureUseCase;
import androidx.camera.core.VideoCaptureUseCase.OnVideoSavedListener;
import androidx.camera.core.VideoCaptureUseCaseConfiguration;
import androidx.camera.core.ViewFinderUseCase;
import androidx.camera.core.ViewFinderUseCaseConfiguration;
import androidx.camera.view.CameraView.CaptureMode;
import androidx.camera.view.CameraView.Quality;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** CameraX use case operation built on @{link androidx.camera.core}. */
final class CameraXModule {
    public static final String TAG = "CameraXModule";

    private static final int MAX_VIEW_DIMENSION = 2000;
    private static final float UNITY_ZOOM_SCALE = 1f;
    private static final float ZOOM_NOT_SUPPORTED = UNITY_ZOOM_SCALE;
    private static final Rational ASPECT_RATIO_16_9 = new Rational(16, 9);
    private static final Rational ASPECT_RATIO_4_3 = new Rational(4, 3);

    private final CameraManager mCameraManager;
    private final ViewFinderUseCaseConfiguration.Builder mViewFinderConfigBuilder;
    private final VideoCaptureUseCaseConfiguration.Builder mVideoCaptureConfigBuilder;
    private final ImageCaptureUseCaseConfiguration.Builder mImageCaptureConfigBuilder;
    private final CameraView mCameraView;
    final AtomicBoolean mVideoIsRecording = new AtomicBoolean(false);
    private CameraView.Quality mQuality = CameraView.Quality.HIGH;
    private CameraView.CaptureMode mCaptureMode = CaptureMode.IMAGE;
    private long mMaxVideoDuration = CameraView.INDEFINITE_VIDEO_DURATION;
    private long mMaxVideoSize = CameraView.INDEFINITE_VIDEO_SIZE;
    private FlashMode mFlash = FlashMode.OFF;
    @Nullable
    private ImageCaptureUseCase mImageCaptureUseCase;
    @Nullable
    private VideoCaptureUseCase mVideoCaptureUseCase;
    @Nullable
    ViewFinderUseCase mViewFinderUseCase;
    @Nullable
    LifecycleOwner mCurrentLifecycle;
    private final LifecycleObserver mCurrentLifecycleObserver =
            new DefaultLifecycleObserver() {
                @Override
                public void onDestroy(LifecycleOwner owner) {
                    if (owner == mCurrentLifecycle) {
                        clearCurrentLifecycle();
                        mViewFinderUseCase.removeViewFinderOutputListener();
                    }
                }
            };
    @Nullable
    private LifecycleOwner mNewLifecycle;
    private float mZoomLevel = UNITY_ZOOM_SCALE;
    @Nullable
    private Rect mCropRegion;
    @Nullable
    private CameraX.LensFacing mCameraLensFacing = LensFacing.BACK;

    CameraXModule(CameraView view) {
        this.mCameraView = view;

        mCameraManager = (CameraManager) view.getContext().getSystemService(Context.CAMERA_SERVICE);

        mViewFinderConfigBuilder =
                new ViewFinderUseCaseConfiguration.Builder().setTargetName("ViewFinder");

        mImageCaptureConfigBuilder =
                new ImageCaptureUseCaseConfiguration.Builder().setTargetName("ImageCapture");

        mVideoCaptureConfigBuilder =
                new VideoCaptureUseCaseConfiguration.Builder().setTargetName("VideoCapture");
    }

    /**
     * Rescales view rectangle with dimensions in [-1000, 1000] to a corresponding rectangle in the
     * sensor coordinate frame.
     */
    private static Rect rescaleViewRectToSensorRect(Rect view, Rect sensor) {
        // Scale width and height.
        int newWidth = Math.round(view.width() * sensor.width() / (float) MAX_VIEW_DIMENSION);
        int newHeight = Math.round(view.height() * sensor.height() / (float) MAX_VIEW_DIMENSION);

        // Scale top/left corner.
        int halfViewDimension = MAX_VIEW_DIMENSION / 2;
        int leftOffset =
                Math.round(
                        (view.left + halfViewDimension)
                                * sensor.width()
                                / (float) MAX_VIEW_DIMENSION)
                        + sensor.left;
        int topOffset =
                Math.round(
                        (view.top + halfViewDimension)
                                * sensor.height()
                                / (float) MAX_VIEW_DIMENSION)
                        + sensor.top;

        // Now, produce the scaled rect.
        Rect scaled = new Rect();
        scaled.left = leftOffset;
        scaled.top = topOffset;
        scaled.right = scaled.left + newWidth;
        scaled.bottom = scaled.top + newHeight;
        return scaled;
    }

    @RequiresPermission(permission.CAMERA)
    public void bindToLifecycle(LifecycleOwner lifecycleOwner) {
        mNewLifecycle = lifecycleOwner;

        if (getMeasuredWidth() > 0 && getMeasuredHeight() > 0) {
            bindToLifecycleAfterViewMeasured();
        }
    }

    @RequiresPermission(permission.CAMERA)
    void bindToLifecycleAfterViewMeasured() {
        if (mNewLifecycle == null) {
            return;
        }

        clearCurrentLifecycle();
        mCurrentLifecycle = mNewLifecycle;
        mNewLifecycle = null;
        if (mCurrentLifecycle.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            mCurrentLifecycle = null;
            throw new IllegalArgumentException("Cannot bind to lifecycle in a destroyed state.");
        }

        int cameraOrientation;
        try {
            String cameraId;
            Set<LensFacing> available = getAvailableCameraLensFacing();

            if (available.isEmpty()) {
                Log.w(TAG, "Unable to bindToLifeCycle since no cameras available");
                mCameraLensFacing = null;
            }

            // Ensure the current camera exists, or default to another camera
            if (mCameraLensFacing != null && !available.contains(mCameraLensFacing)) {
                Log.w(TAG, "Camera does not exist with direction " + mCameraLensFacing);

                // Default to the first available camera direction
                mCameraLensFacing = available.iterator().next();

                Log.w(TAG, "Defaulting to primary camera with direction " + mCameraLensFacing);
            }

            // Do not attempt to create use cases for a null cameraLensFacing. This could occur if
            // the
            // user explicitly sets the LensFacing to null, or if we determined there
            // were no available cameras, which should be logged in the logic above.
            if (mCameraLensFacing == null) {
                return;
            }

            cameraId = CameraX.getCameraWithLensFacing(mCameraLensFacing);
            if (cameraId == null) {
                return;
            }
            CameraInfo cameraInfo = CameraX.getCameraInfo(cameraId);
            cameraOrientation = cameraInfo.getSensorRotationDegrees();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to bind to lifecycle.", e);
        }

        // Set the preferred aspect ratio as 4:3 if it is IMAGE only mode. Set the preferred aspect
        // ratio as 16:9 if it is VIDEO or MIXED mode. Then, it will be WYSIWYG when the view finder
        // is
        // in CENTER_INSIDE mode.
        if (getCaptureMode() == CaptureMode.IMAGE) {
            mImageCaptureConfigBuilder.setTargetAspectRatio(ASPECT_RATIO_4_3);
            mViewFinderConfigBuilder.setTargetAspectRatio(ASPECT_RATIO_4_3);
        } else {
            mImageCaptureConfigBuilder.setTargetAspectRatio(ASPECT_RATIO_16_9);
            mViewFinderConfigBuilder.setTargetAspectRatio(ASPECT_RATIO_16_9);
        }

        mImageCaptureConfigBuilder.setTargetRotation(getDisplaySurfaceRotation());
        mImageCaptureConfigBuilder.setLensFacing(mCameraLensFacing);
        mImageCaptureUseCase = new ImageCaptureUseCase(mImageCaptureConfigBuilder.build());

        mVideoCaptureConfigBuilder.setTargetRotation(getDisplaySurfaceRotation());
        mVideoCaptureConfigBuilder.setLensFacing(mCameraLensFacing);
        mVideoCaptureUseCase = new VideoCaptureUseCase(mVideoCaptureConfigBuilder.build());
        mViewFinderConfigBuilder.setLensFacing(mCameraLensFacing);

        int relativeCameraOrientation = getRelativeCameraOrientation(false);

        if (relativeCameraOrientation == 90 || relativeCameraOrientation == 270) {
            mViewFinderConfigBuilder.setTargetResolution(
                    new Size(getMeasuredHeight(), getMeasuredWidth()));
        } else {
            mViewFinderConfigBuilder.setTargetResolution(
                    new Size(getMeasuredWidth(), getMeasuredHeight()));
        }

        mViewFinderUseCase = new ViewFinderUseCase(mViewFinderConfigBuilder.build());
        mViewFinderUseCase.setOnViewFinderOutputUpdateListener(
                output -> {
                    boolean needReverse = cameraOrientation != 0 && cameraOrientation != 180;
                    int textureWidth =
                            needReverse
                                    ? output.getTextureSize().getHeight()
                                    : output.getTextureSize().getWidth();
                    int textureHeight =
                            needReverse
                                    ? output.getTextureSize().getWidth()
                                    : output.getTextureSize().getHeight();
                    onViewfinderSourceDimensUpdated(textureWidth, textureHeight);
                    setSurfaceTexture(output.getSurfaceTexture());
                });

        if (getCaptureMode() == CaptureMode.IMAGE) {
            CameraX.bindToLifecycle(mCurrentLifecycle, mImageCaptureUseCase, mViewFinderUseCase);
        } else if (getCaptureMode() == CaptureMode.VIDEO) {
            CameraX.bindToLifecycle(mCurrentLifecycle, mVideoCaptureUseCase, mViewFinderUseCase);
        } else {
            CameraX.bindToLifecycle(
                    mCurrentLifecycle, mImageCaptureUseCase, mVideoCaptureUseCase,
                    mViewFinderUseCase);
        }
        setZoomLevel(mZoomLevel);
        mCurrentLifecycle.getLifecycle().addObserver(mCurrentLifecycleObserver);
        // Enable flash setting in ImageCaptureUseCase after use cases are created and binded.
        setFlash(getFlash());
    }

    public void open() {
        throw new UnsupportedOperationException(
                "Explicit open/close of camera not yet supported. Use bindtoLifecycle() instead.");
    }

    public void close() {
        throw new UnsupportedOperationException(
                "Explicit open/close of camera not yet supported. Use bindtoLifecycle() instead.");
    }

    public void takePicture(OnImageCapturedListener listener) {
        if (mImageCaptureUseCase == null) {
            return;
        }

        if (getCaptureMode() == CaptureMode.VIDEO) {
            throw new IllegalStateException("Can not take picture under VIDEO capture mode.");
        }

        if (listener == null) {
            throw new IllegalArgumentException("OnImageCapturedListener should not be empty");
        }

        mImageCaptureUseCase.takePicture(listener);
    }

    public void takePicture(File saveLocation, OnImageSavedListener listener) {
        if (mImageCaptureUseCase == null) {
            return;
        }

        if (getCaptureMode() == CaptureMode.VIDEO) {
            throw new IllegalStateException("Can not take picture under VIDEO capture mode.");
        }

        if (listener == null) {
            throw new IllegalArgumentException("OnImageSavedListener should not be empty");
        }

        ImageCaptureUseCase.Metadata metadata = new ImageCaptureUseCase.Metadata();
        metadata.isReversedHorizontal = mCameraLensFacing == LensFacing.FRONT;
        mImageCaptureUseCase.takePicture(saveLocation, listener, metadata);
    }

    public void startRecording(File file, OnVideoSavedListener listener) {
        if (mVideoCaptureUseCase == null) {
            return;
        }

        if (getCaptureMode() == CaptureMode.IMAGE) {
            throw new IllegalStateException("Can not record video under IMAGE capture mode.");
        }

        if (listener == null) {
            throw new IllegalArgumentException("OnVideoSavedListener should not be empty");
        }

        mVideoIsRecording.set(true);
        mVideoCaptureUseCase.startRecording(
                file,
                new VideoCaptureUseCase.OnVideoSavedListener() {
                    @Override
                    public void onVideoSaved(File savedFile) {
                        mVideoIsRecording.set(false);
                        listener.onVideoSaved(savedFile);
                    }

                    @Override
                    public void onError(
                            VideoCaptureUseCase.UseCaseError useCaseError,
                            String message,
                            @Nullable Throwable cause) {
                        mVideoIsRecording.set(false);
                        Log.e(TAG, message, cause);
                        listener.onError(useCaseError, message, cause);
                    }
                });
    }

    public void stopRecording() {
        if (mVideoCaptureUseCase == null) {
            return;
        }

        mVideoCaptureUseCase.stopRecording();
    }

    public boolean isRecording() {
        return mVideoIsRecording.get();
    }

    // TODO(b/124269166): Rethink how we can handle permissions here.
    @SuppressLint("MissingPermission")
    public void setCameraByLensFacing(@Nullable LensFacing lensFacing) {
        // Setting same lens facing is a no-op, so check for that first
        if (mCameraLensFacing != lensFacing) {
            // If we're not bound to a lifecycle, just update the camera that will be opened when we
            // attach to a lifecycle.
            mCameraLensFacing = lensFacing;

            if (mCurrentLifecycle != null) {
                // Re-bind to lifecycle with new camera
                bindToLifecycle(mCurrentLifecycle);
            }
        }
    }

    @RequiresPermission(permission.CAMERA)
    public boolean hasCameraWithLensFacing(LensFacing lensFacing) {
        String cameraId;
        try {
            cameraId = CameraX.getCameraWithLensFacing(lensFacing);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to query lens facing.", e);
        }

        return cameraId != null;
    }

    @Nullable
    public LensFacing getLensFacing() {
        return mCameraLensFacing;
    }

    public void toggleCamera() {
        // TODO(b/124269166): Rethink how we can handle permissions here.
        @SuppressLint("MissingPermission")
        Set<LensFacing> availableCameraLensFacing = getAvailableCameraLensFacing();

        if (availableCameraLensFacing.isEmpty()) {
            return;
        }

        if (mCameraLensFacing == null) {
            setCameraByLensFacing(availableCameraLensFacing.iterator().next());
            return;
        }

        if (mCameraLensFacing == LensFacing.BACK
                && availableCameraLensFacing.contains(LensFacing.FRONT)) {
            setCameraByLensFacing(LensFacing.FRONT);
            return;
        }

        if (mCameraLensFacing == LensFacing.FRONT
                && availableCameraLensFacing.contains(LensFacing.BACK)) {
            setCameraByLensFacing(LensFacing.BACK);
            return;
        }
    }

    public void focus(Rect focus, Rect metering) {
        if (mViewFinderUseCase == null) {
            // Nothing to focus on since we don't yet have a viewfinder
            return;
        }

        Rect rescaledFocus;
        Rect rescaledMetering;
        try {
            Rect sensorRegion;
            if (mCropRegion != null) {
                sensorRegion = mCropRegion;
            } else {
                sensorRegion = getSensorSize(getActiveCamera());
            }
            rescaledFocus = rescaleViewRectToSensorRect(focus, sensorRegion);
            rescaledMetering = rescaleViewRectToSensorRect(metering, sensorRegion);
        } catch (Exception e) {
            Log.e(TAG, "Failed to rescale the focus and metering rectangles.", e);
            return;
        }

        mViewFinderUseCase.focus(rescaledFocus, rescaledMetering);
    }

    public float getZoomLevel() {
        return mZoomLevel;
    }

    public void setZoomLevel(float zoomLevel) {
        // Set the zoom level in case it is set before binding to a lifecycle
        this.mZoomLevel = zoomLevel;

        if (mViewFinderUseCase == null) {
            // Nothing to zoom on yet since we don't have a viewfinder. Defer calculating crop
            // region.
            return;
        }

        Rect sensorSize;
        try {
            sensorSize = getSensorSize(getActiveCamera());
            if (sensorSize == null) {
                Log.e(TAG, "Failed to get the sensor size.");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get the sensor size.", e);
            return;
        }

        float minZoom = getMinZoomLevel();
        float maxZoom = getMaxZoomLevel();

        if (this.mZoomLevel < minZoom) {
            Log.e(TAG, "Requested zoom level is less than minimum zoom level.");
        }
        if (this.mZoomLevel > maxZoom) {
            Log.e(TAG, "Requested zoom level is greater than maximum zoom level.");
        }
        this.mZoomLevel = Math.max(minZoom, Math.min(maxZoom, this.mZoomLevel));

        float zoomScaleFactor =
                (maxZoom == minZoom) ? minZoom : (this.mZoomLevel - minZoom) / (maxZoom - minZoom);
        int minWidth = Math.round(sensorSize.width() / maxZoom);
        int minHeight = Math.round(sensorSize.height() / maxZoom);
        int diffWidth = sensorSize.width() - minWidth;
        int diffHeight = sensorSize.height() - minHeight;
        float cropWidth = diffWidth * zoomScaleFactor;
        float cropHeight = diffHeight * zoomScaleFactor;

        Rect cropRegion =
                new Rect(
                        /*left=*/ (int) Math.ceil(cropWidth / 2 - 0.5f),
                        /*top=*/ (int) Math.ceil(cropHeight / 2 - 0.5f),
                        /*right=*/ (int) Math.floor(sensorSize.width() - cropWidth / 2 + 0.5f),
                        /*bottom=*/ (int) Math.floor(sensorSize.height() - cropHeight / 2 + 0.5f));

        if (cropRegion.width() < 50 || cropRegion.height() < 50) {
            Log.e(TAG, "Crop region is too small to compute 3A stats, so ignoring further zoom.");
            return;
        }
        this.mCropRegion = cropRegion;

        mViewFinderUseCase.zoom(cropRegion);
    }

    public float getMinZoomLevel() {
        return UNITY_ZOOM_SCALE;
    }

    public float getMaxZoomLevel() {
        try {
            CameraCharacteristics characteristics =
                    mCameraManager.getCameraCharacteristics(getActiveCamera());
            Float maxZoom =
                    characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (maxZoom == null) {
                return ZOOM_NOT_SUPPORTED;
            }
            if (maxZoom == ZOOM_NOT_SUPPORTED) {
                return ZOOM_NOT_SUPPORTED;
            }
            return maxZoom;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get SCALER_AVAILABLE_MAX_DIGITAL_ZOOM.", e);
        }
        return ZOOM_NOT_SUPPORTED;
    }

    public boolean isZoomSupported() {
        return getMaxZoomLevel() != ZOOM_NOT_SUPPORTED;
    }

    // TODO(b/124269166): Rethink how we can handle permissions here.
    @SuppressLint("MissingPermission")
    private void rebindToLifecycle() {
        if (mCurrentLifecycle != null) {
            bindToLifecycle(mCurrentLifecycle);
        }
    }

    int getRelativeCameraOrientation(boolean compensateForMirroring) {
        int rotationDegrees;
        try {
            String cameraId = CameraX.getCameraWithLensFacing(getLensFacing());
            CameraInfo cameraInfo = CameraX.getCameraInfo(cameraId);
            rotationDegrees = cameraInfo.getSensorRotationDegrees(getDisplaySurfaceRotation());
            if (compensateForMirroring) {
                rotationDegrees = (360 - rotationDegrees) % 360;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query camera", e);
            rotationDegrees = 0;
        }

        return rotationDegrees;
    }

    public CameraView.Quality getQuality() {
        return mQuality;
    }

    public void setQuality(Quality quality) {
        if (quality != Quality.HIGH) {
            throw new UnsupportedOperationException("Only supported Quality is HIGH");
        }
        this.mQuality = quality;
    }

    public void invalidateView() {
        transformPreview();
        updateViewInfo();
    }

    void clearCurrentLifecycle() {
        if (mCurrentLifecycle != null) {
            // Remove previous use cases
            CameraX.unbind(mImageCaptureUseCase, mVideoCaptureUseCase, mViewFinderUseCase);
        }

        mCurrentLifecycle = null;
    }

    private Rect getSensorSize(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    }

    String getActiveCamera() throws CameraInfoUnavailableException {
        return CameraX.getCameraWithLensFacing(mCameraLensFacing);
    }

    @UiThread
    private void transformPreview() {
        int viewfinderWidth = getViewFinderWidth();
        int viewfinderHeight = getViewFinderHeight();
        int displayOrientation = getDisplayRotationDegrees();

        Matrix matrix = new Matrix();

        // Apply rotation of the display
        int rotation = -displayOrientation;

        int px = (int) Math.round(viewfinderWidth / 2d);
        int py = (int) Math.round(viewfinderHeight / 2d);

        matrix.postRotate(rotation, px, py);

        if (displayOrientation == 90 || displayOrientation == 270) {
            // Swap width and height
            float xScale = viewfinderWidth / (float) viewfinderHeight;
            float yScale = viewfinderHeight / (float) viewfinderWidth;

            matrix.postScale(xScale, yScale, px, py);
        }

        setTransform(matrix);
    }

    // Update view related information used in use cases
    private void updateViewInfo() {
        if (mImageCaptureUseCase != null) {
            mImageCaptureUseCase.setTargetAspectRatio(new Rational(getWidth(), getHeight()));
            mImageCaptureUseCase.setTargetRotation(getDisplaySurfaceRotation());
        }

        if (mVideoCaptureUseCase != null) {
            mVideoCaptureUseCase.setTargetRotation(getDisplaySurfaceRotation());
        }
    }

    @RequiresPermission(permission.CAMERA)
    private Set<LensFacing> getAvailableCameraLensFacing() {
        // Start with all camera directions
        Set<LensFacing> available = new LinkedHashSet<>(Arrays.asList(LensFacing.values()));

        // If we're bound to a lifecycle, remove unavailable cameras
        if (mCurrentLifecycle != null) {
            if (!hasCameraWithLensFacing(LensFacing.BACK)) {
                available.remove(LensFacing.BACK);
            }

            if (!hasCameraWithLensFacing(LensFacing.FRONT)) {
                available.remove(LensFacing.FRONT);
            }
        }

        return available;
    }

    public FlashMode getFlash() {
        return mFlash;
    }

    public void setFlash(FlashMode flash) {
        this.mFlash = flash;

        if (mImageCaptureUseCase == null) {
            // Do nothing if there is no imageCaptureUseCase
            return;
        }

        mImageCaptureUseCase.setFlashMode(flash);
    }

    public void enableTorch(boolean torch) {
        if (mViewFinderUseCase == null) {
            return;
        }
        mViewFinderUseCase.enableTorch(torch);
    }

    public boolean isTorchOn() {
        if (mViewFinderUseCase == null) {
            return false;
        }
        return mViewFinderUseCase.isTorchOn();
    }

    public Context getContext() {
        return mCameraView.getContext();
    }

    public int getWidth() {
        return mCameraView.getWidth();
    }

    public int getHeight() {
        return mCameraView.getHeight();
    }

    public int getDisplayRotationDegrees() {
        return CameraOrientationUtil.surfaceRotationToDegrees(getDisplaySurfaceRotation());
    }

    protected int getDisplaySurfaceRotation() {
        return mCameraView.getDisplaySurfaceRotation();
    }

    public void setSurfaceTexture(SurfaceTexture st) {
        mCameraView.setSurfaceTexture(st);
    }

    private int getViewFinderWidth() {
        return mCameraView.getViewFinderWidth();
    }

    private int getViewFinderHeight() {
        return mCameraView.getViewFinderHeight();
    }

    private int getMeasuredWidth() {
        return mCameraView.getMeasuredWidth();
    }

    private int getMeasuredHeight() {
        return mCameraView.getMeasuredHeight();
    }

    void setTransform(final Matrix matrix) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mCameraView.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            setTransform(matrix);
                        }
                    });
        } else {
            mCameraView.setTransform(matrix);
        }
    }

    /**
     * Notify the view that the source dimensions have changed.
     *
     * <p>This will allow the view to layout the viewfinder to display the correct aspect ratio.
     *
     * @param width  width of camera source buffers.
     * @param height height of camera source buffers.
     */
    private void onViewfinderSourceDimensUpdated(int width, int height) {
        mCameraView.onViewfinderSourceDimensUpdated(width, height);
    }

    public CameraView.CaptureMode getCaptureMode() {
        return mCaptureMode;
    }

    public void setCaptureMode(CameraView.CaptureMode captureMode) {
        this.mCaptureMode = captureMode;
        rebindToLifecycle();
    }

    public long getMaxVideoDuration() {
        return mMaxVideoDuration;
    }

    public void setMaxVideoDuration(long duration) {
        mMaxVideoDuration = duration;
    }

    public long getMaxVideoSize() {
        return mMaxVideoSize;
    }

    public void setMaxVideoSize(long size) {
        mMaxVideoSize = size;
    }

    public boolean isPaused() {
        return false;
    }
}
