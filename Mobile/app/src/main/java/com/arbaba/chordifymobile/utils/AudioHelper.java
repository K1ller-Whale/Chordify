package com.arbaba.chordifymobile.utils;

import android.media.MediaRecorder;
import android.util.Log;
import java.io.File;
import java.io.IOException;

public class AudioHelper {
    private MediaRecorder recorder;
    private File outputFile;

    public void startRecording(File cacheDir) throws IOException {
        // Create file in cache directory
        outputFile = File.createTempFile("chord_recording", ".m4a", cacheDir);

        recorder = new MediaRecorder();
        // VOICE_RECOGNITION is often optimized for clear audio input
        recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        // Use AAC High Quality
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(128000);
        recorder.setAudioSamplingRate(44100); // Standard CD quality, Librosa loves this

        recorder.setOutputFile(outputFile.getAbsolutePath());
        recorder.prepare();
        recorder.start();
    }

    public File stopRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                // Occurs if recording was less than 1 second. Return null.
                return null;
            } finally {
                recorder.release();
                recorder = null;
            }
        }

        // Debugging: Check if file actually has data
        if (outputFile != null) {
            long size = outputFile.length();
            Log.d("AudioHelper", "File created: " + outputFile.getAbsolutePath() + " | Size: " + size + " bytes");
            if (size < 1000) {
                // If file is tiny (< 1KB), it's likely silent/empty
                Log.e("AudioHelper", "WARNING: Audio file is suspiciously small.");
            }
        }
        return outputFile;
    }
}