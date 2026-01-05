package com.arbaba.chordifymobile.api;

import com.arbaba.chordifymobile.model.ChordResponse;
import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

public interface ChordApiService {
    @Multipart
    @POST("/predict")
    Call<ChordResponse> predictChord(@Part MultipartBody.Part file);
}