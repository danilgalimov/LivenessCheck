package name.danilgalimov.livenesscheck;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.util.Log;

import java.io.IOException;
import java.util.List;


public class TheCamera implements Camera.PreviewCallback {
    private static final String TAG = "TheCamera";

    private Activity mActivity;

    private Camera mCamera = null;
    private TheCameraPainter painter = null;
    private int mCameraId;
    private SurfaceTexture surfaceTexture = null;
    private int MAGIC_TEXTURE_ID = 10;
    private byte[] buf = null;

    private boolean open_flag = false;

    public TheCamera(Activity activity) {
        this.mActivity = activity;
    }

    public synchronized void open(TheCameraPainter theCameraPainter) {

        if (open_flag) return;

        painter = theCameraPainter;
        setFrontCameraId();
        open_flag = true;

        if (mCamera != null) return;

        Log.i(TAG, "TheCamera open");

        try {
            mCamera = Camera.open(mCameraId);
            Parameters cameraParameters = mCamera.getParameters();

            List<int[]> fpsRanges = cameraParameters.getSupportedPreviewFpsRange();
            int[] maxFpsRange = fpsRanges.get(fpsRanges.size() - 1);
            Log.i(TAG, "Set FPS Range (" + maxFpsRange[0] + "," + maxFpsRange[1] + ")");

            cameraParameters.setPreviewFormat(ImageFormat.NV21);
            cameraParameters.setPreviewSize(640, 480);
            cameraParameters.setPreviewFpsRange(maxFpsRange[0], maxFpsRange[1]);
            mCamera.setParameters(cameraParameters);


            cameraParameters = mCamera.getParameters();
            Size previewSize = cameraParameters.getPreviewSize();
            int size = previewSize.width * previewSize.height;
            size = size * ImageFormat.getBitsPerPixel(cameraParameters.getPreviewFormat()) / 8;
            buf = new byte[size];
        } catch (Exception e) {
            Log.e(TAG, "Can't open the camera " + mCameraId);
            e.printStackTrace();
            close();
            return;
        }

        surfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.addCallbackBuffer(buf);
        mCamera.setPreviewCallbackWithBuffer(this);
        mCamera.setDisplayOrientation(0);

        mCamera.startPreview();

    }


    public synchronized void close() {
        if (!open_flag) return;

        open_flag = false;

        if (mCamera == null) return;

        Log.i(TAG, "TheCamera close");

        mCamera.setPreviewCallback(null);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }


    @Override
    public void onPreviewFrame(byte[] arg0, Camera arg1) {
        Parameters parameters = arg1.getParameters();
        Size size = parameters.getPreviewSize();

        try {
            painter.processingImage(arg0, size.width, size.height);
        } catch (Exception e) {
            e.printStackTrace();
            mActivity.finish();
            return;
        }

        mCamera.addCallbackBuffer(buf);
    }

    private void setFrontCameraId() {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                try {
                    mCameraId = i;
                    break;
                } catch (RuntimeException runtimeException) {
                    runtimeException.printStackTrace();
                }
            }
        }
    }
}