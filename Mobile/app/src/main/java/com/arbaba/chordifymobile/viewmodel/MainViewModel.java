package com.arbaba.chordifymobile.viewmodel;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.arbaba.chordifymobile.api.RetrofitClient;
import com.arbaba.chordifymobile.model.ChordResponse;
import com.arbaba.chordifymobile.utils.AudioHelper;

import java.io.File;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainViewModel extends ViewModel {

    private final MutableLiveData<String> chordText = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);

    private final AudioHelper audioHelper = new AudioHelper();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public LiveData<String> getChordText() { return chordText; }
    public LiveData<Boolean> getIsRecording() { return isRecording; }

    // Logic: Record for 4 seconds, then send
    public void startListeningLoop(File cacheDir) {
        if (Boolean.TRUE.equals(isRecording.getValue())) return;

        isRecording.setValue(true);
        chordText.setValue("Listening...");

        try {
            audioHelper.startRecording(cacheDir);

            // Stop after 4.5 seconds (matching your model's 100 frames roughly)
            handler.postDelayed(() -> {
                File file = audioHelper.stopRecording();
                uploadAudio(file);
                isRecording.setValue(false);
            }, 4500);

        } catch (Exception e) {
            chordText.setValue("Error: " + e.getMessage());
            isRecording.setValue(false);
        }
    }

    private void uploadAudio(File file) {
        if (file == null || !file.exists()) return;
        chordText.setValue("Analyzing...");

        // Prepare the file for Retrofit
        RequestBody reqFile = RequestBody.create(MediaType.parse("audio/mp4"), file);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), reqFile);

        // Send to Python API
        RetrofitClient.getInstance().getMyApi().predictChord(body).enqueue(new Callback<ChordResponse>() {
            @Override
            public void onResponse(Call<ChordResponse> call, Response<ChordResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String chord = response.body().getChord();
                    float conf = response.body().getConfidence();

                    // Format: "Am (98%)"
                    chordText.setValue(String.format("%s (%.0f%%)", chord, conf * 100));
                } else {
                    chordText.setValue("Server Error: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ChordResponse> call, Throwable t) {
                chordText.setValue("Connection Failed");
                Log.e("API", "Error", t);
            }
        });
    }
}