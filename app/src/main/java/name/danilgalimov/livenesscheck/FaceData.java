package name.danilgalimov.livenesscheck;

import com.vdt.face_recognition.sdk.RawSample;


class FaceData {

    public RawSample sample;
    public boolean lost;
    public boolean weak;
    public int frame_id;
    public long lost_time;

    public FaceData(RawSample sample) {
        this.lost = false;
        this.sample = sample;
    }
}