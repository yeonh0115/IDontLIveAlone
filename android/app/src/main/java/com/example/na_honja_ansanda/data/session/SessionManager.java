package com.example.na_honja_ansanda.data.session;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.na_honja_ansanda.data.model.User;

public class SessionManager {
    private static final String PREF_NAME = "user_prefs";
    private static final String KEY_USER_NO = "logged_in_user_no";
    private static SessionManager instance;

    private final SharedPreferences prefs;
    private final SecureSessionStore secureStore;
    private User loginUser;

    private SessionManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.secureStore = new SecureSessionStore(context);
    }

    public static synchronized SessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    public boolean createSession(User user) {
        if (user == null || user.getUserNo() == null || user.getUserNo() <= 0 || user.getUserId() == null) return false;
        if (!secureStore.save(user.getSessionToken())) {
            clearSession();
            return false;
        }
        boolean stored = prefs.edit().putInt(KEY_USER_NO, user.getUserNo())
                .putString("logged_in_user_id", user.getUserId())
                .putString("logged_in_username", user.getUsername())
                .putString("logged_in_email", user.getEmail()).commit();
        if (!stored) {
            clearSession();
            return false;
        }
        user.setSessionToken(null);
        this.loginUser = user;
        return true;
    }

    public int getUserNo() {
        return prefs.getInt(KEY_USER_NO, -1);
    }

    public User getLoginUser() {
        if (loginUser == null && getUserNo() > 0 && prefs.contains("logged_in_user_id")) {
            loginUser = new User();
            loginUser.setUserNo(getUserNo());
            loginUser.setUserId(prefs.getString("logged_in_user_id", null));
            loginUser.setUsername(prefs.getString("logged_in_username", ""));
            loginUser.setEmail(prefs.getString("logged_in_email", ""));
        }
        return loginUser;
    }

    public String getAuthorizationHeader() {
        if (getUserNo() <= 0) return null;
        String token = secureStore.read();
        return token == null || token.isEmpty() ? null : "Bearer " + token;
    }

    public void clearSession() {
        this.loginUser = null;
        secureStore.clear();
        prefs.edit().clear().apply();
    }

    public boolean isLoggedIn() {
        return getUserNo() != -1;
    }
}
