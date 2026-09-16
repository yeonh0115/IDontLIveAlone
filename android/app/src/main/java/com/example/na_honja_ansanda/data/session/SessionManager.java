package com.example.na_honja_ansanda.data.session;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.na_honja_ansanda.data.model.User;

public class SessionManager {
    private static final String PREF_NAME = "user_prefs";
    private static final String KEY_USER_NO = "logged_in_user_no";
    private static SessionManager instance;

    private final SharedPreferences prefs;
    private User loginUser;

    private SessionManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    public void createSession(User user) {
        this.loginUser = user;
        prefs.edit().putInt(KEY_USER_NO, user.getUserNo()).apply();
    }

    public int getUserNo() {
        return prefs.getInt(KEY_USER_NO, -1);
    }

    public User getLoginUser() {
        return loginUser;
    }

    public void clearSession() {
        this.loginUser = null;
        prefs.edit().clear().apply();
    }

    public boolean isLoggedIn() {
        return getUserNo() != -1;
    }
}