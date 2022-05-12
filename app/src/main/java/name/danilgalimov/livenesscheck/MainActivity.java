package name.danilgalimov.livenesscheck;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.vdt.face_recognition.sdk.FacerecService;

import java.util.Timer;
import java.util.TimerTask;


//all init parameters in init_settings
public class MainActivity extends AppCompatActivity {

    private static FacerecService sFacerecService = null;

    public static FacerecService getFacerecService() {
        return sFacerecService;
    }

    private VidRecDemo mVidRecDemo = null;
    private TheCamera mTheCamera = null;

    private final String[] permissions_str = new String[]{
            Manifest.permission.CAMERA
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // check and request permissions
        int granted_count = 0;

        for (String perstr : permissions_str) {
            if (ContextCompat.checkSelfPermission(this, perstr) == PackageManager.PERMISSION_GRANTED) {
                granted_count++;
            }
        }

        if (granted_count < permissions_str.length) {
            ActivityCompat.requestPermissions(this, permissions_str, 0);
        } else {
            starting();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            // Set the content to appear under the system bars so that the
                            // content doesn't resize when the system bars hide and show.
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            // Hide the nav bar and status bar
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void starting() {
        setContentView(R.layout.loading);

        sFacerecService = FacerecService.createService(
                getApplicationInfo().nativeLibraryDir + "/libfacerec.so",
                getApplicationInfo().dataDir + "/fsdk/conf/facerec",
                getApplicationInfo().dataDir + "/fsdk/license");

        try {
            sFacerecService.getLicenseState();
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        new Thread(new LoadThread(this, sFacerecService)).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ask_again = false;

        for (int i = 0; i < permissions.length; ++i) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                ask_again = true;
                break;
            }
        }

        if (ask_again) {
            final Activity this_activity = this;
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    ActivityCompat.requestPermissions(this_activity, permissions_str, 0);
                }
            }, 2000);
        } else {
            starting();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVidRecDemo != null) {
            mTheCamera.open(mVidRecDemo);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mTheCamera != null) mTheCamera.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mTheCamera != null) mTheCamera.close();
        if (mVidRecDemo != null) {
            mVidRecDemo.closeDrawThread();
            mVidRecDemo.dispose();
        }
    }

    private static class LoadThread implements Runnable {

        MainActivity mMainActivity;
        FacerecService mFacerecService;

        public LoadThread(MainActivity mainActivity, FacerecService facerecService) {
            this.mMainActivity = mainActivity;
            this.mFacerecService = facerecService;
        }


        public void run() {
            try {
                final TheCamera theCamera = new TheCamera(mMainActivity);

                final VidRecDemo vidRecDemo = new VidRecDemo(mMainActivity);

                mMainActivity.runOnUiThread(() -> {
                    mMainActivity.mTheCamera = theCamera;
                    mMainActivity.mVidRecDemo = vidRecDemo;
                    mMainActivity.showForm();
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    public void whatToDo(String helpText) {
        if (mTheCamera != null) mTheCamera.close();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(helpText)
                .setCancelable(false)
                .setNegativeButton("Продолжить",
                        (dialog, id) -> {
                            dialog.cancel();
                            if (mVidRecDemo != null) {
                                mTheCamera.open(mVidRecDemo);
                            }
                        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    public void showForm() {
        setContentView(R.layout.activity_main);

        ImageView mainImageView = findViewById(R.id.mainImageView);
        mVidRecDemo.setMainImageView(mainImageView);

        mTheCamera.open(mVidRecDemo);

        TextView verdictTextView = findViewById(R.id.verdictTextView);
        mVidRecDemo.setVerdictTextView(verdictTextView);
        TextView checkTypeTextView = findViewById(R.id.checkTypeTextView);
        mVidRecDemo.setCheckTypeTextView(checkTypeTextView);

        Button quitButton = findViewById(R.id.quitButton);
        quitButton.setOnClickListener(v -> finishAffinity());

        Handler handler = new Handler() {
            public void handleMessage(Message msg) {
                try {
                    mVidRecDemo.updateImageViews();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        mVidRecDemo.startDrawThread(handler);
    }
}
