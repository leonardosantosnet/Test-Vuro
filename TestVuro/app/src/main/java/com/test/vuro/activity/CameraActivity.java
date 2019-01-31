package com.test.vuro.activity;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.os.Handler;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import com.test.vuro.service.GPSInfoService;

import java.util.Date;
import java.text.SimpleDateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;

class CameraActivity implements Runnable  {
    private static final String TAG = CameraActivity.class.getSimpleName();
    private Handler handler = new Handler();

    private CameraDevice cameraDevice;
    private ImageReader imageReader;

    private Queue<String> cameraIds;

    private String currentCameraId;
    private boolean cameraClosed;

    private String geoLat;
    private String geoLong;


    private TreeMap<String, byte[]> picturesTaken;

    private final Activity activity;
    final Context context;
    final CameraManager manager;


    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    CameraActivity(Activity activity){
        this.activity = activity;
        this.context = activity.getApplicationContext();
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    @Override
    public void run() {
        try {
            if (Thread.interrupted()) throw new InterruptedException();
            //handler.postDelayed(this, 20000);
            handler.postDelayed(this, 180000);


            LocalBroadcastManager.getInstance(activity).registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                                geoLat  = intent.getStringExtra(GPSInfoService.EXTRA_LATITUDE);
                                geoLong = intent.getStringExtra(GPSInfoService.EXTRA_LONGITUDE);
                        }
                    }, new IntentFilter(GPSInfoService.ACTION_LOCATION_BROADCAST)
            );


            this.picturesTaken = new TreeMap<>();
            this.cameraIds = new LinkedList<>();

            try {
                final String[] cameraIds = manager.getCameraIdList();
                if (cameraIds.length > 0) {
                    this.cameraIds.addAll(Arrays.asList(cameraIds));
                    this.currentCameraId = this.cameraIds.poll();
                    openCamera();
                }

            } catch (final CameraAccessException e) {
                Log.e(TAG, "Exception occurred while accessing the list of cameras", e);
            }

            Log.d(TAG, "test ");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private void openCamera() {
        Log.d(TAG, "opening camera " + currentCameraId);
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(currentCameraId, stateCallback, handler);
            }
        } catch (final CameraAccessException e) {
            Log.e(TAG, " exception occurred while opening camera " + currentCameraId, e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            closeCamera();
        }
    };


    private final ImageReader.OnImageAvailableListener onImageAvailableListener = (ImageReader imReader) -> {
        final Image image = imReader.acquireLatestImage();
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);

        saveImageToDisk(bytes);

        image.close();
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraClosed = false;
            Log.d(TAG, "camera " + camera.getId() + " opened");
            cameraDevice = camera;
            Log.i(TAG, "Taking picture from camera " + camera.getId());
            //Take the picture after some delay. It may resolve getting a black dark photos.
            new Handler().postDelayed(() -> {
                try {
                    takePicture();
                } catch (final CameraAccessException e) {
                    Log.e(TAG, " exception occurred while taking picture from " + currentCameraId, e);
                }
            }, 500);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, " camera " + camera.getId() + " disconnected");
            if (cameraDevice != null && !cameraClosed) {
                cameraClosed = true;
                cameraDevice.close();
            }
        }

        @Override
        public void onClosed(@NonNull CameraDevice camera) {
            cameraClosed = true;
            Log.d(TAG, "camera " + camera.getId() + " closed");
            //once the current camera has been closed, start taking another picture
            if (!cameraIds.isEmpty()) {
                takeAnotherPicture();
            }
        }


        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "camera in error, int code " + error);
            if (cameraDevice != null && !cameraClosed) {
                cameraDevice.close();
            }
        }
    };

    int getOrientation() {
        final int rotation = this.activity.getWindowManager().getDefaultDisplay().getRotation();
        return ORIENTATIONS.get(rotation);
    }

    private void takePicture() throws CameraAccessException {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
        Size[] jpegSizes = null;
        StreamConfigurationMap streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streamConfigurationMap != null) {
            jpegSizes = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
        }
        final boolean jpegSizesNotEmpty = jpegSizes != null && 0 < jpegSizes.length;
        int width = jpegSizesNotEmpty ? jpegSizes[0].getWidth() : 640;
        int height = jpegSizesNotEmpty ? jpegSizes[0].getHeight() : 480;
        final ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);

        final List<Surface> outputSurfaces = new ArrayList<>();
        outputSurfaces.add(reader.getSurface());
        final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

        captureBuilder.addTarget(reader.getSurface());
        captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation());
        reader.setOnImageAvailableListener(onImageAvailableListener, handler);



        cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            session.capture(captureBuilder.build(), captureListener, handler);
                        } catch (final CameraAccessException e) {
                            Log.e(TAG, " exception occurred while accessing " + currentCameraId, e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    }
                }
                , handler);
    }


    private void saveImageToDisk(final byte[] bytes) {

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inMutable = true;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length).copy(Bitmap.Config.RGB_565, true);

        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(55);

        int x = 60;
        int y = bitmap.getHeight()-160;

        canvas.drawText("Latitude: " + geoLat + "  longitude: " + geoLong, x, y, paint);

        String cameraId = this.cameraDevice == null ? UUID.randomUUID().toString() : this.cameraDevice.getId();
        cameraId = cameraId == "0"  ? "back" : "front";

        final File file = new File(Environment.getExternalStorageDirectory() + "/" + cameraId + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg");


        try (OutputStream output = new FileOutputStream(file)) {

            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output); //Output as JPG with maximum quality.
            output.flush();
            output.close();
        } catch (final IOException e) {
            Log.e(TAG, "Exception occurred while saving picture to external storage ", e);
        }

    }

    private void takeAnotherPicture() {
        this.currentCameraId = this.cameraIds.poll();
        openCamera();
    }

    private void closeCamera() {
        Log.d(TAG, "closing camera " + cameraDevice.getId());
        if (null != cameraDevice && !cameraClosed) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }





}
