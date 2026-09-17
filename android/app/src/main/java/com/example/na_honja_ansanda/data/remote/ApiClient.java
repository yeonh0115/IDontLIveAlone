package com.example.na_honja_ansanda.data.remote;

import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {
    private static final String BASE_URL = "https://idontlivealone.onrender.com";
    private static ApiService apiService;

    public static synchronized ApiService getApiService() {
        if (apiService == null) {
            OkHttpClient okHttpClient = new OkHttpClient.Builder().build();

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();
            apiService = retrofit.create(ApiService.class);
        }
        return apiService;
    }
}