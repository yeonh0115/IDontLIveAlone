package com.example.na_honja_ansanda.ui.main;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.na_honja_ansanda.R;
import com.example.na_honja_ansanda.ui.mypage.MyPageFragment;
import com.example.na_honja_ansanda.ui.report.ReportFragment;
import com.example.na_honja_ansanda.ui.security.SecurityHubFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        setupNavigation();
        checkPermissions();

        if (savedInstanceState == null) {
            switchFragment(new HomeFragment());
        }
    }

    private void setupNavigation() {
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int id = item.getItemId();

            if (id == R.id.nav_home) selectedFragment = new HomeFragment();
            else if (id == R.id.nav_report) selectedFragment = new ReportFragment();
            else if (id == R.id.nav_security) selectedFragment = new SecurityHubFragment();
            else if (id == R.id.nav_mypage) selectedFragment = new MyPageFragment();

            if (selectedFragment != null) {
                switchFragment(selectedFragment);
                return true;
            }
            return false;
        });
    }

    public void updateBottomNavigationToHome() {
        if (bottomNav != null) {
            bottomNav.getMenu().findItem(R.id.nav_home).setChecked(true);
        }
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_container, fragment)
                .commit();
    }

    private void checkPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1000);
        }
    }
}