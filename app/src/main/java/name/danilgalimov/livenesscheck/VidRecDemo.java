package name.danilgalimov.livenesscheck;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.FragmentManager;

import com.vdt.face_recognition.sdk.ActiveLiveness;
import com.vdt.face_recognition.sdk.Capturer;
import com.vdt.face_recognition.sdk.FacerecService;
import com.vdt.face_recognition.sdk.MatchFoundCallbackData;
import com.vdt.face_recognition.sdk.Point;
import com.vdt.face_recognition.sdk.RawImage;
import com.vdt.face_recognition.sdk.RawSample;
import com.vdt.face_recognition.sdk.Recognizer;
import com.vdt.face_recognition.sdk.StiPersonOutdatedCallbackData;
import com.vdt.face_recognition.sdk.TrackingCallbackData;
import com.vdt.face_recognition.sdk.TrackingLostCallbackData;
import com.vdt.face_recognition.sdk.VideoWorker;
import com.vdt.face_recognition.sdk.utils.Converter_YUV_NV_2_ARGB;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;


public class VidRecDemo implements TheCameraPainter {

    private static final String TAG = "VidRecDemo";
    private static final String SETTINGS_NAME = "SETTINGS";

    ActiveLiveness.ActiveLivenessStatus st;

    private final MainActivity activity;
    private ImageView mainImageView = null;
    private ProgressBar mProgressLevel;
    private TextView mTextViewVerdict, mTextViewCheckType;

    private Capturer capturer = null;
    private final VideoWorker videoWorker;

    //This thread used for background async initialization of some Face SDK components for speedup.
    private final Thread init_thread;

    private final String method_recognizer;
    private final float threshold;

    private final LinkedBlockingQueue<Pair<Integer, Bitmap>> frames = new LinkedBlockingQueue<>();
    private final int stream_id = 0;

    Thread drawThread = null;
    private final DrawingData drawingData = new DrawingData();


    public VidRecDemo(final MainActivity activity) {
        this.activity = activity;

        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        Log.v(TAG, "Start VidRecDemo");
        SharedPreferences shared_settings = activity.getSharedPreferences(SETTINGS_NAME, 0);

        // get recognizer settings
        int method_index = shared_settings.getInt("method index", 0);
        switch (method_index) {
            case 0:
                method_recognizer = shared_settings.getString("rec_method0", "recognizer_latest_v30.xml");
                threshold = Float.parseFloat(shared_settings.getString("threshold0", "6250"));
                break;
            case 1:
                method_recognizer = shared_settings.getString("rec_method1", "method9v30mask_recognizer.xml");
                threshold = Float.parseFloat(shared_settings.getString("threshold1", "3600"));
                break;
            default:
                method_recognizer = shared_settings.getString("rec_method2", "method8v7_recognizer.xml");
                threshold = Float.parseFloat(shared_settings.getString("threshold2", "7000"));
                break;
        }

        final FacerecService service = MainActivity.getService();
        Log.v(TAG, "Loaded settings");

        Log.v(TAG, "Starting init thread");
        init_thread = new Thread(new Runnable() {
            public void run() {
                synchronized (init_thread) {
                    // create Capturer
                    FacerecService.Config capturer_conf = service.new Config("common_capturer_blf_fda_front.xml");
                    capturer = service.createCapturer(capturer_conf);
                    Log.v(TAG, "init thread: created capturer1");

                    // create manual_capturer and recognizer
                    FacerecService.Config manual_capturer_conf = service.new Config("manual_capturer_fda.xml");
                    final Recognizer recognizer = service.createRecognizer(method_recognizer, false, false, false);
                    Log.v(TAG, "init thread: created recognizer " + method_recognizer);

                    final Capturer manual_capturer = service.createCapturer(manual_capturer_conf);
                    Log.v(TAG, "init thread: created capturer2");

                    // free resources
                    recognizer.dispose();
                    manual_capturer.dispose();
                    Log.v(TAG, "init thread: done");
                }
            }
        });

        init_thread.start();

        Vector<ActiveLiveness.CheckType> checks = new Vector<>();
        checks.add(ActiveLiveness.CheckType.BLINK);
        checks.add(ActiveLiveness.CheckType.TURN_DOWN);
        checks.add(ActiveLiveness.CheckType.TURN_UP);

        //create videoWorker
        Log.v(TAG, "creating worker");
        videoWorker = service.createVideoWorker(
                new VideoWorker.Params()
                        .video_worker_config(
                                service.new Config("video_worker_fdatracker_blf_fda_front.xml")
                                        .overrideParameter("search_k", 10)
                                        .overrideParameter("recognizer_processing_less_memory_consumption", 0)
                                        .overrideParameter("downscale_rawsamples_to_preferred_size", 0)
                                        .overrideParameter("enable_active_liveness", 1)
                        )
                        .recognizer_ini_file(method_recognizer)
                        .streams_count(1)
                        .processing_threads_count(1)
                        .matching_threads_count(1)
                        .short_time_identification_enabled(true)
                        .short_time_identification_distance_threshold(threshold)
                        .short_time_identification_outdate_time_seconds(5)
                        .active_liveness_checks_order(checks)
        );

        //add callbacks
        videoWorker.addTrackingCallbackU(new TrackingCallbacker());
        videoWorker.addTrackingLostCallbackU(new TrackingLostCallbacker());
        videoWorker.addMatchFoundCallbackU(new MatchFoundCallbacker());
        videoWorker.addStiPersonOutdatedCallbackU(new StiPersonOutdatedCallbacker());
        Log.v(TAG, "creating worker done");
    }

    @Override
    public void processingImage(byte[] data, int width, int height) {

        // get RawImage
        RawImage frame = new RawImage(
                width,
                height,
                RawImage.Format.FORMAT_YUV_NV21,
                data);

        int[] argb = Converter_YUV_NV_2_ARGB.convert_yuv_nv_2_argb(false, data, width, height);

        Bitmap immut_bitmap = Bitmap.createBitmap(
                argb,
                width,
                height,
                Bitmap.Config.ARGB_8888);
        Bitmap mut_bitmap = immut_bitmap.copy(Bitmap.Config.ARGB_8888, true);

        int frame_id = videoWorker.addVideoFrame(frame, stream_id);
        frames.offer(new Pair<>(frame_id, mut_bitmap));

        videoWorker.checkExceptions();
    }


    private class TrackingCallbacker implements VideoWorker.TrackingCallbackU {

        public void call(TrackingCallbackData data) {

            if (data.stream_id != stream_id)
                return;

            //get frame
            Bitmap frame;
            while (true) {

                if (frames.size() == 0) {
                    return;
                }

                if (frames.peek().first == data.frame_id) {
                    frame = frames.poll().second;
                    break;
                } else {
                    Log.v(TAG, "Skiped " + stream_id + ": " + frames.poll().first);
                }
            }

            //update data
            synchronized (drawingData) {

                drawingData.frame = frame;
                drawingData.frame_id = data.frame_id;
                drawingData.updated = true;

                for (int i = 0; i < data.samples.size(); i++) {

                    RawSample sample = data.samples.get(i);
                    int id = sample.getID();

					st = data.samples_active_liveness_status.get(i);

                    if (!drawingData.faces.containsKey(id)) {
                        FaceData faceData = new FaceData(sample);
                        drawingData.faces.put(id, faceData);
                    }

                    FaceData face = drawingData.faces.get(id);

                    face.frame_id = sample.getFrameID();
                    face.lost = false;
                    face.weak = data.samples_weak.get(i);
                    face.sample = sample;
                }
            }
        }
    }


    private class TrackingLostCallbacker implements VideoWorker.TrackingLostCallbackU {

        public void call(TrackingLostCallbackData data) {

            if (data.stream_id != stream_id)
                return;

            Log.i(TAG, "tracking lost callback: "
                    + "  track_id: " + data.track_id
                    + "  sti_person_id_set: " + data.sti_person_id_set
                    + "  sti_person_id: " + data.sti_person_id
            );

            synchronized (drawingData) {

                if (drawingData.faces.isEmpty()) {
                    return;
                }

                FaceData face = drawingData.faces.get(data.track_id);

                face.lost = true;
                face.lost_time = System.currentTimeMillis();

                if (data.best_quality_sample != null) {
                    face.sample = data.best_quality_sample;
                }

                drawingData.updated = true;
            }
        }
    }

    private class StiPersonOutdatedCallbacker implements VideoWorker.StiPersonOutdatedCallbackU {

        public void call(StiPersonOutdatedCallbackData data) {

            if (data.stream_id != stream_id)
                return;

            Log.i(TAG, "sti person outdated callback: "
                    + "  sti_person_id: " + data.sti_person_id
            );
        }
    }

    private class MatchFoundCallbacker implements VideoWorker.MatchFoundCallback {

        public void call(MatchFoundCallbackData data) {

            if (data.stream_id != stream_id)
                return;

            data.sample.getID();

            synchronized (drawingData) {

                if (drawingData.faces.isEmpty()) {
                    return;
                }

                drawingData.updated = true;
            }
        }
    }


    public void startDrawThread(final Handler handler) {

        if (drawThread != null) {
            closeDrawThread();
        }

        mainImageView = activity.findViewById(R.id.mainImageView);
        mProgressLevel = activity.findViewById(R.id.progressBarProgressLevel);
        mTextViewCheckType = activity.findViewById(R.id.textViewCheckType);
        mTextViewVerdict = activity.findViewById(R.id.textViewVerdict);

        drawThread = new Thread(() -> {

            while (true) {

                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (drawingData.updated) {
                    handler.sendEmptyMessage(0);
                }

            }
        });
        drawThread.start();
    }


    public void updateImageViews() {
        synchronized (drawingData) {

            //draw only first 7 faces
            int count = 0;
            SortedSet<Integer> keys = new TreeSet<>(drawingData.faces.keySet());

            for (Integer key : keys) {

                FaceData face = drawingData.faces.get(key);

                if (face.frame_id == drawingData.frame_id && !face.lost) {

                    //get points
                    Vector<Point> points = face.sample.getLandmarks();

                    //compute center point
                    Point center_point = new Point(0, 0);
                    for (Point p : points) {
                        center_point.x += p.x;
                        center_point.y += p.y;
                    }

                    center_point.x /= points.size();
                    center_point.y /= points.size();

                    //compute radius
                    float radius = 0;
                    for (Point p : points) {
                        radius += distance(p, center_point);
                    }
                    radius /= points.size();
                    radius *= 2;

                    //set paint
                    Paint paint = new Paint();
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2);

                    //dash weak faces
                    if (face.weak) {
                        DashPathEffect dashPath = new DashPathEffect(new float[]{20, 10}, 0);
                        paint.setPathEffect(dashPath);
                    }
                    paint.setColor(0xffff0000);


                    //draw
                    Canvas main_canvas = new Canvas(drawingData.frame);
                    main_canvas.drawCircle(center_point.x, center_point.y, radius, paint);
                }

                //processing lost face
                if (face.lost) {

                    long cur_time = System.currentTimeMillis();

                    //milliseconds
                    long alive_lost_time = 1000;
                    if (cur_time - face.lost_time > alive_lost_time) {
                        mTextViewVerdict.setText("Статус: посмотрите в камеру");
                        drawingData.faces.remove(key);
                        continue;
                    }
                }


                if (count > 1) {
                    mTextViewVerdict.setText("Статус: для прохождения проверки в кадре должно находится одно лицо");
                    break;
                }
                count++;

            }

            if (st != null) {
                switch (st.verdict.toString()) {
                    case "ALL_CHECKS_PASSED":
                        mTextViewVerdict.setText("Статус: все проверки пройдены.");
                        break;
                    case "CURRENT_CHECK_PASSED":
                        mTextViewVerdict.setText("Статус: текущая проверка пройдена.");
                        break;
                    case "CHECK_FAIL":
                        mTextViewVerdict.setText("Статус: проверка не удалась.");
                        break;
                    case "WAITING_FACE_ALIGN":
                        mTextViewVerdict.setText("Статус: в ожидании нейтрального положения лица.");
                        break;
                    case "NOT_COMPUTED":
                        mTextViewVerdict.setText("Статус: Liveness не оценивается.");
                        break;
                    case "IN_PROGRESS":
                        mTextViewVerdict.setText("Статус: проверка в процессе.");
                        break;
                }
                switch (st.check_type.toString()) {
                    case "BLINK":
                        mTextViewCheckType.setText("Тип проверки: моргните.");
                        break;
                    case "TURN_UP":
                        mTextViewCheckType.setText("Тип проверки: поднимите голову.");
                        break;
                    case "TURN_DOWN":
                        mTextViewCheckType.setText("Тип проверки: опустите голову.");
                        break;
                }
                mProgressLevel.setProgress((int) (st.progress_level * 100));
            }

            mainImageView.setImageBitmap(drawingData.frame);
            drawingData.updated = false;
        }
    }

    public void closeDrawThread() {
        if (drawThread != null) {
            drawThread.interrupt();
        }
    }


    private float distance(Point p1, Point p2) {
        return (float) Math.sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y));
    }

    public void dispose() {
        capturer.dispose();
        videoWorker.dispose();
    }

}
