package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.model.DeviceInfo;
import com.example.na_honja_ansanda.data.model.DevicePairing;
import com.example.na_honja_ansanda.data.model.User;
import com.example.na_honja_ansanda.data.remote.ApiService;
import com.example.na_honja_ansanda.dto.DeviceClaimRequest;
import com.google.gson.Gson;
import okhttp3.Request;
import okio.Buffer;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;

public class DevicePairingTest {
    @Test public void connectionCodesPreserveLeadingZeroAndRejectNonNumericInput() {
        assertEquals("00123456", DevicePairing.normalizedCode("00 12-3456"));
        for (String invalid : new String[]{null, "", "1234567", "123456789", "12a45678", "１２３４５６７８"}) {
            assertEquals("", DevicePairing.normalizedCode(invalid));
        }
    }

    @Test public void devicePreviewRequiresKnownRolesAndClaimCanReadOwner() {
        for (String role : new String[]{"CAMERA", "SENSOR", "REPORT"}) assertTrue(DevicePairing.isKnownRole(role));
        assertFalse(DevicePairing.isKnownRole(null));
        assertFalse(DevicePairing.isKnownRole("ADMIN"));
        DeviceInfo result = new Gson().fromJson("{\"deviceId\":\"device-7\",\"role\":\"REPORT\",\"paired\":true,\"userNo\":37}", DeviceInfo.class);
        assertTrue(result.isPaired());
        assertEquals(Integer.valueOf(37), result.getUserNo());
    }

    @Test public void loginAcceptsOpaqueSessionAndLegacyUserJson() {
        Gson gson = new Gson();
        User legacy = gson.fromJson("{\"userNo\":37,\"userId\":\"example\"}", User.class);
        assertNull(legacy.getSessionToken());
        User current = gson.fromJson("{\"userNo\":37,\"userId\":\"example\",\"sessionToken\":\"test-session\",\"sessionExpiresAt\":\"2026-09-23T12:00:00\"}", User.class);
        assertEquals("test-session", current.getSessionToken());
    }

    @Test public void pairingUsesSessionHeaderAndCodeWithoutCallerChosenOwner() throws Exception {
        ApiService service = new Retrofit.Builder().baseUrl("https://example.test/")
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService.class);
        Request preview = service.previewDevice("Bearer test-session", "00123456").request();
        assertEquals("/api/devices/pairing/00123456", preview.url().encodedPath());
        assertEquals("Bearer test-session", preview.header("Authorization"));
        Request claim = service.claimDevice("Bearer test-session", new DeviceClaimRequest("00123456")).request();
        Buffer body = new Buffer();
        claim.body().writeTo(body);
        assertEquals("{\"code\":\"00123456\"}", body.readUtf8());
        assertEquals("POST", claim.method());
        assertNull(claim.url().query());
        assertEquals("DELETE", service.unlinkDevice("Bearer test-session", "device-7").request().method());
        assertEquals("/api/users/logout", service.logout("Bearer test-session").request().url().encodedPath());
    }
}
