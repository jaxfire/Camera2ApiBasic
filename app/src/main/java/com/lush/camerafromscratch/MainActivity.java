package com.lush.camerafromscratch;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private TextureView mTextureView;
    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.d("jim", "TextureView listener. The TextureView is now available");
            Log.d("jim", "width:height = " + width + ":" + height);
            // When our TextureView is available we get its width and height. Our setupCamera method
            // requires these and so we call setupCamera only at this point in time.
            setupCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            // Here we receive a reference to the camera device
            mCameraDevice = camera;
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            // Clean up Camera resources
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            // Clean up Camera resources
            camera.close();
            mCameraDevice = null;
        }
    };

    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size o1, Size o2) {
            // signum = The return value is -1 if the specified value is negative; 0 if the specified
            // value is zero; and 1 if the specified value is positive.)

            Log.d("jim", "Compare value: " + Long.signum((long) o1.getWidth() * o1.getHeight() / (long) o2.getWidth() * o2.getHeight()));

            return Long.signum((long) o1.getWidth() * o1.getHeight() / (long) o2.getWidth() * o2.getHeight());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextureView = findViewById(R.id.textureView);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // We start and stop our background worker thread in onResume and onPause respectively.
        startBackgroundThread();

        // TextureView takers a while to inflate. If ready for use now, then great. If not then add
        // a listener to notify us when it is ready.

        if(mTextureView.isAvailable()) {
            Log.d("jim", "TextureView is ready in onResume");

            // The TextureView is already available so we can query it for its width and height.
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            Log.d("jim", "TextureView is NOT ready in onResume");
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        // When switching app, we should free up the camera resource.

        closeCamera();

        stopBackgroundThread();

        super.onPause();
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            Log.d("jim", "Freeing up camera resource");
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void setupCamera(int width, int height) {

        // We need the camera's ID. Most devices will have multiple cameras so we need to traverse
        // the list of available camera and get the one we are interested. Back facing camera.
        // The camera ID is used for selecting and connecting to the actual camera device.
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // Try/catch for getCameraIdList()
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                // For each available camera we can get that specific camera's characteristics
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                // Ignore front facing cameras.
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                // The SCALER_STREAM_CONFIGURATION_MAP contains all the lists of the various resolutions
                // for camera preview, camera, video, raw camera etc.
                StreamConfigurationMap map =  cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // Work out if we're in portrait mode or not. If we're in portrait mode we need to
                // swap the width and height values provided by our TextureView.

                int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
                Log.d("jim", "setupCamera - deviceOrientation: " + deviceOrientation);

                int totalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation);
                Log.d("jim", "setupCamera - totalRotation: " + totalRotation);

                // A result of 90 or 270 indicates that the phone is in portrait mode.
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;

                int rotatedWidth = width;
                int rotatedHeight = height;

                // All the previews from the Camera are in landscape but our TextureView is in portrait
                // (when the device is orientated to portrait) so for them to work with each other's values
                // we need to swap our dimensions

                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                Log.d("jim", "rotatedWidth: " + rotatedWidth);
                Log.d("jim", "rotatedHeight: " + rotatedHeight);

                // Set up preview display size. Use map (passing in SurfaceTexture) to get a list of
                // all of the preview resolutions available.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);

                Log.d("jim", "Chosen preview size: " + mPreviewSize.getWidth() + ":" + mPreviewSize.getHeight());

                // So the sensor supports multiple preview resolutions and we just found the closest
                // matching preview resolution to our TextureView.

                // If it's not front facing then it has to be back facing (which is what we want)
                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread() {
        // Could we use the cameraId here instead? See other examples.
        mBackgroundHandlerThread = new HandlerThread("threadName");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mBackgroundHandlerThread = null;
        mBackgroundHandler = null;
    }

    // The orientation/rotation sensor in the camera may not always match the orientation/rotation
    // sensor in the device. We must handle this.

    // The camera (sensor?) also has a number of preview resolutions. These resolutions tend to be
    // set up for landscape mode. We must handle this for portrait mode.
    // Swap height and width around to match the resolutions in the preview table so we can select
    // a preview for our TextureView.


    // Convert the sensors device orientation to degrees.
    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceOrientation) {

        int sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Log.d("jim", "sensorToDeviceRotation - sensorOrientation: " + sensorOrientation);

        deviceOrientation = ORIENTATIONS.get(deviceOrientation);
        Log.d("jim", "sensorToDeviceRotation - deviceOrientation: " + deviceOrientation);

        Log.d("jim", "sensorToDeviceRotation - return value: " + (sensorOrientation + deviceOrientation) % 360);
        return (sensorOrientation + deviceOrientation) % 360;
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height) {

        // TODO: This logic seems flawed. If no perfect aspect ratio match is found then get the
        // closest aspect ratio match? See how the Google sample app does this.

        // Is the resolution from the sensor big enough for our display?
        Set<Size> aspectRationMatchAndBigEnough = new HashSet<>();
        Set<Size> bigEnough = new HashSet<>();

        // Traverse through the list of preview resolutions supplied by the sensor.
        for (Size option : choices) {
            Log.d("jim", "option: " + option.getWidth() + ":" + option.getHeight());

            // Aspect ratio check. Do they have equivalent aspect ratios. e.g. 16:9 == 160:90.
            if (option.getHeight() == option.getWidth() * height / width) {

                // Width/Height check. Are the width and height of this preview option greater than
                // or equal to the width and height of our display?
                if (option.getWidth() >= width && option.getHeight() >= height) {
                    aspectRationMatchAndBigEnough.add(option);
                }
            } else {
                // Width/Height check. Are the width and height of this preview option greater than
                // or equal to the width and height of our display?
                if (option.getWidth() >= width && option.getHeight() >= height) {
                    bigEnough.add(option);
                }
            }
        }

        if (aspectRationMatchAndBigEnough.size() > 0) {
            Log.d("jim", "Aspect ratio match found and big enough");
            // Find the minimum size we can use from the list of possible options.
            return Collections.min(aspectRationMatchAndBigEnough, new CompareSizeByArea());
        } else if (bigEnough.size() > 0){
            Log.d("jim", "No aspect ratio match found but big enough");
            // Find the minimum size from the options which don't match our TextureViews aspect ration.
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            Log.d("jim", "No aspect ration match found and non that are big enough");
            // If non are big enough then return the first from the choices array... better than nothing
            // but this could be improved. The largest from the choices array would be an improvement.
            return choices[0];
        }
    }
}
