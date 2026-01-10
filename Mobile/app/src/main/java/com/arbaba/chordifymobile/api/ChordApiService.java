package com.arbaba.chordifymobile.api;

import com.arbaba.chordifymobile.model.ChordResponse;
import com.arbaba.chordifymobile.model.TimeStampedChordResponse;
import okhttp3.MultipartBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.PartMap;
import java.util.Map;

public interface ChordApiService {
    @Multipart
    @POST("/predict")
    Call<ChordResponse> predictChord(@Part MultipartBody.Part file);

    @Multipart
    @POST("/predict_time_stamps")
    Call<TimeStampedChordResponse> predictTimeStamps(
            @Part MultipartBody.Part file,
            @Part("timestamps") okhttp3.RequestBody timestamps
    );

    @Multipart
    @POST("/extract_full_chroma")
    Call<ResponseBody> extractFullChroma(@Part MultipartBody.Part file);

    @GET("/")
    Call<Map<String, Object>> healthCheck();
}