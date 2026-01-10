package com.arbaba.chordifymobile.api;

import android.content.Context;
import android.content.SharedPreferences;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RetrofitClient {
    private static final String DEFAULT_BASE_URL = "http://192.168.1.37:8000/";
    private static RetrofitClient instance = null;
    private ChordApiService myApi;
    private Retrofit retrofit;
    private String currentBaseUrl;

    private RetrofitClient() {
        currentBaseUrl = DEFAULT_BASE_URL;
        initializeRetrofit();
    }

    private void initializeRetrofit() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(logging).build();

        retrofit = new Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        myApi = retrofit.create(ChordApiService.class);
    }

    public static synchronized RetrofitClient getInstance() {
        if (instance == null) {
            instance = new RetrofitClient();
        }
        return instance;
    }

    public static void updateBaseUrl(String newBaseUrl) {
        if (instance != null && !instance.currentBaseUrl.equals(newBaseUrl)) {
            instance.currentBaseUrl = newBaseUrl;
            instance.initializeRetrofit();
        }
    }

    public static void loadBaseUrlFromPreferences(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("chordify_prefs", 0);
        String savedUrl = prefs.getString("api_url", DEFAULT_BASE_URL);
        updateBaseUrl(savedUrl);
    }

    public ChordApiService getMyApi() {
        return myApi;
    }
}