package com.example.na_honja_ansanda.data.remote;

import com.example.na_honja_ansanda.data.model.IntegratedLog;
import com.example.na_honja_ansanda.data.model.FaceTaskResponse;
import com.example.na_honja_ansanda.data.model.ReportResponse;
import com.example.na_honja_ansanda.data.model.User;
import com.example.na_honja_ansanda.dto.DoorLockRequest;
import com.example.na_honja_ansanda.dto.LoginRequest;
import com.example.na_honja_ansanda.dto.SignUpRequest;

import java.util.List;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {

    @POST("api/users/login")
    Call<User> login(@Body LoginRequest request);

    @POST("api/users/sign-up")
    Call<ResponseBody> signup(@Body SignUpRequest request);


    @POST("api/users/update-door-lock")
    Call<ResponseBody> updateDoorLock(@Body DoorLockRequest request);

    @Multipart
    @POST("api/face/register")
    Call<FaceTaskResponse> registerFace(
            @Part("userId") RequestBody userId,
            @Part MultipartBody.Part file1,
            @Part MultipartBody.Part file2,
            @Part MultipartBody.Part file3
    );

    @GET("api/face/tasks/{taskId}")
    Call<FaceTaskResponse> getFaceTask(@Path("taskId") String taskId, @Query("userId") String userId);

    // SQL 스키마 명세에 맞춰 @GET 엔드포인트와 리턴 데이터 타입을 IntegratedLog로 완전히 묶어 최적화했습니다.
    @GET("api/logs")
    Call<List<IntegratedLog>> getIntegratedLogs(@Query("userNo") int userNo);

    @GET("api/reports/{userNo}")
    Call<List<ReportResponse>> getUserReports(@Path("userNo") int userNo);
}
