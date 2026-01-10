package com.arbaba.chordifymobile.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.arbaba.chordifymobile.api.RetrofitClient;
import com.arbaba.chordifymobile.model.ChordResponse;
import com.arbaba.chordifymobile.model.HistoryEntry;
import com.arbaba.chordifymobile.model.TimeStampedChordResponse;
import com.arbaba.chordifymobile.repository.ChordRepository;
import com.arbaba.chordifymobile.utils.AudioHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainViewModel extends AndroidViewModel {

    private final MutableLiveData<String> chordText = new MutableLiveData<>("Tap to Record");
    private final MutableLiveData<Boolean> isRecording = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> recordingDuration = new MutableLiveData<>(0);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isLoading = new MutableLiveData<>(false);
    private final MutableLiveData<File> recordedAudioFile = new MutableLiveData<>();
    private final MutableLiveData<byte[]> chromaImageData = new MutableLiveData<>();
    private final MutableLiveData<TimeStampedChordResponse> timeStampedResults = new MutableLiveData<>();

    private final AudioHelper audioHelper = new AudioHelper();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ChordRepository repository;
    private File currentRecordingFile;
    private Runnable durationRunnable;
    private int durationSeconds = 0;

    public MainViewModel(Application application) {
        super(application);
        repository = new ChordRepository(application);
        chordText.setValue("Tap to Record");
    }

    public LiveData<String> getChordText() { return chordText; }
    public LiveData<Boolean> getIsRecording() { return isRecording; }
    public LiveData<Integer> getRecordingDuration() { return recordingDuration; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<Boolean> getIsLoading() { return isLoading; }
    public LiveData<File> getRecordedAudioFile() { return recordedAudioFile; }
    public LiveData<byte[]> getChromaImageData() { return chromaImageData; }
    public LiveData<TimeStampedChordResponse> getTimeStampedResults() { return timeStampedResults; }
    public LiveData<List<HistoryEntry>> getAllHistory() { return repository.getAllHistory(); }

    public void startRecording(File cacheDir) {
        if (Boolean.TRUE.equals(isRecording.getValue())) return;

        try {
            currentRecordingFile = File.createTempFile("chord_recording", ".m4a", cacheDir);
            audioHelper.startRecording(cacheDir);
            isRecording.setValue(true);
            chordText.setValue("Recording...");
            durationSeconds = 0;
            recordingDuration.setValue(0);
            errorMessage.setValue(null);

            // Start duration counter
            durationRunnable = new Runnable() {
                @Override
                public void run() {
                    if (Boolean.TRUE.equals(isRecording.getValue())) {
                        durationSeconds++;
                        recordingDuration.setValue(durationSeconds);
                        handler.postDelayed(this, 1000);
                    }
                }
            };
            handler.post(durationRunnable);

        } catch (Exception e) {
            errorMessage.setValue("Recording failed: " + e.getMessage());
            isRecording.setValue(false);
            Log.e("MainViewModel", "Start recording error", e);
        }
    }

    public void stopRecording() {
        if (!Boolean.TRUE.equals(isRecording.getValue())) return;

        try {
            File file = audioHelper.stopRecording();
            if (file != null && file.exists()) {
                currentRecordingFile = file;
                recordedAudioFile.setValue(file);
                isRecording.setValue(false);
                chordText.setValue("Processing...");
                
                if (durationRunnable != null) {
                    handler.removeCallbacks(durationRunnable);
                }
                
                // Auto-upload after stopping
                uploadAudio(file);
            } else {
                isRecording.setValue(false);
                errorMessage.setValue("Recording failed - file not created");
                chordText.setValue("Tap to Record");
            }
        } catch (Exception e) {
            isRecording.setValue(false);
            errorMessage.setValue("Stop recording failed: " + e.getMessage());
            chordText.setValue("Tap to Record");
            Log.e("MainViewModel", "Stop recording error", e);
        }
    }

    public void uploadAudio(File file) {
        if (file == null || !file.exists()) {
            errorMessage.setValue("Audio file not found");
            return;
        }

        isLoading.setValue(true);
        chordText.setValue("Analyzing...");
        errorMessage.setValue(null);

        RequestBody reqFile = RequestBody.create(MediaType.parse("audio/mp4"), file);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), reqFile);

        RetrofitClient.getInstance().getMyApi().predictChord(body).enqueue(new Callback<ChordResponse>() {
            @Override
            public void onResponse(Call<ChordResponse> call, Response<ChordResponse> response) {
                isLoading.setValue(false);
                if (response.isSuccessful() && response.body() != null) {
                    String chord = response.body().getChord();
                    float conf = response.body().getConfidence();

                    chordText.setValue(String.format("%s (%.1f%%)", chord, conf));
                    
                    // Save to history
                    HistoryEntry entry = new HistoryEntry(chord, conf);
                    entry.setAudioFilePath(file.getAbsolutePath());
                    repository.insert(entry);
                } else {
                    errorMessage.setValue("Server Error: " + response.code());
                    chordText.setValue("Tap to Record");
                }
            }

            @Override
            public void onFailure(Call<ChordResponse> call, Throwable t) {
                isLoading.setValue(false);
                errorMessage.setValue("Connection Failed: " + t.getMessage());
                chordText.setValue("Tap to Record");
                Log.e("API", "Error", t);
            }
        });
    }

    public void uploadAudioForChroma(File file) {
        if (file == null || !file.exists()) {
            errorMessage.setValue("Audio file not found");
            return;
        }

        isLoading.setValue(true);
        errorMessage.setValue(null);

        RequestBody reqFile = RequestBody.create(MediaType.parse("audio/mp4"), file);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", file.getName(), reqFile);

        RetrofitClient.getInstance().getMyApi().extractFullChroma(body).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                isLoading.setValue(false);
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        byte[] imageBytes = response.body().bytes();
                        chromaImageData.setValue(imageBytes);
                    } catch (Exception e) {
                        errorMessage.setValue("Failed to process image: " + e.getMessage());
                        Log.e("MainViewModel", "Chroma image error", e);
                    }
                } else {
                    errorMessage.setValue("Chroma extraction failed: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                isLoading.setValue(false);
                errorMessage.setValue("Connection Failed: " + t.getMessage());
                Log.e("API", "Chroma error", t);
            }
        });
    }

    public void uploadAudioForTimeStamps(File file, List<com.arbaba.chordifymobile.model.TimeStamp> timestamps) {
        if (file == null || !file.exists()) {
            errorMessage.setValue("Audio file not found");
            return;
        }

        isLoading.setValue(true);
        errorMessage.setValue(null);

        // Convert timestamps to JSON
        Gson gson = new Gson();
        JsonArray jsonArray = new JsonArray();
        for (com.arbaba.chordifymobile.model.TimeStamp ts : timestamps) {
            JsonObject obj = new JsonObject();
            obj.addProperty("start", ts.getStart());
            obj.addProperty("end", ts.getEnd());
            jsonArray.add(obj);
        }
        String timestampsJson = gson.toJson(jsonArray);

        RequestBody reqFile = RequestBody.create(MediaType.parse("audio/mp4"), file);
        MultipartBody.Part filePart = MultipartBody.Part.createFormData("file", file.getName(), reqFile);
        RequestBody timestampsPart = RequestBody.create(MediaType.parse("text/plain"), timestampsJson);

        RetrofitClient.getInstance().getMyApi().predictTimeStamps(filePart, timestampsPart)
                .enqueue(new Callback<TimeStampedChordResponse>() {
            @Override
            public void onResponse(Call<TimeStampedChordResponse> call, Response<TimeStampedChordResponse> response) {
                isLoading.setValue(false);
                if (response.isSuccessful() && response.body() != null) {
                    timeStampedResults.setValue(response.body());
                } else {
                    errorMessage.setValue("Time-stamped prediction failed: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<TimeStampedChordResponse> call, Throwable t) {
                isLoading.setValue(false);
                errorMessage.setValue("Connection Failed: " + t.getMessage());
                Log.e("API", "Time-stamped error", t);
            }
        });
    }

    public void deleteHistoryEntry(HistoryEntry entry) {
        repository.delete(entry);
    }

    public void clearAllHistory() {
        repository.deleteAll();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (durationRunnable != null) {
            handler.removeCallbacks(durationRunnable);
        }
    }
}
