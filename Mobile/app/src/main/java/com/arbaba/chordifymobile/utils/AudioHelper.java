package com.arbaba.chordifymobile.utils;

import android.media.MediaRecorder;
import java.io.File;
import java.io.IOException;

public class AudioHelper {
    private MediaRecorder recorder;
    private File outputFile;

    // Start recording to a temp file
    public void startRecording(File cacheDir) throws IOException {
        // Create a temp file ending in .m4a
        outputFile = File.createTempFile("chord_audio", ".m4a", cacheDir);

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOutputFile(outputFile.getAbsolutePath());

        // Standard quality settings
        recorder.setAudioSamplingRate(22050);
        recorder.setAudioEncodingBitRate(128000);

        recorder.prepare();
        recorder.start();
    }

    // Stop recording and return the File object
    public File stopRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException stopException) {
                // Handle case where recording was too short (instant tap)
            }
            recorder.release();
            recorder = null;
        }
        return outputFile;
    }
}