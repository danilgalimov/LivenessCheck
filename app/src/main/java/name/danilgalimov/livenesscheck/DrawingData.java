package name.danilgalimov.livenesscheck;

import android.graphics.Bitmap;

import java.util.HashMap;


class DrawingData {

    public boolean updated;
    public Bitmap frame = null;
    public int frame_id;
    //Pair<track_id, face>
    public HashMap<Integer, FaceData> faces = new HashMap<>();

    public DrawingData() {
        this.updated = false;
    }
}