package com.lush.camerafromscratch;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

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
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
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

                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                Log.d("jim", "rotatedWidth: " + rotatedWidth);
                Log.d("jim", "rotatedHeight: " + rotatedHeight);

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
}
