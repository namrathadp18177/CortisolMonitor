package com.example.myapplication;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

/**
 * PatientMainActivity is the primary container for the patient-facing UI.
 *
 * It hosts a BottomNavigationView with five tabs:
 *   1. Home          – Cortisol Test + DHEA Test (coming soon)
 *   2. Assessments   – All questionnaires
 *   3. Add Data (+)  – User-generated health data (future backend)
 *   4. Insights      – Placeholder for future content
 *   5. Profile       – Results, history, account, logout
 *
 * This Activity replaces DashboardActivity as the main patient entry point.
 * DashboardActivity is retained for reference but is no longer navigated to directly.
 */
public class PatientMainActivity extends AppCompatActivity {

    private static final String TAG = "PatientMainActivity";

    // Tab fragment instances (kept alive to preserve scroll state across tab switches)
    private HomeFragment        homeFragment;
    private QuestionnairesFragment questionnairesFragment;
    private PlusFragment        plusFragment;
    private InsightsFragment    insightsFragment;
    private ProfileFragment     profileFragment;

    private Fragment            activeFragment;

    private MaterialToolbar     toolbar;
    private BottomNavigationView bottomNav;
    private final Handler       handler = new Handler(Looper.getMainLooper());
    private boolean             backPressedOnce = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_main);

        setupToolbar();
        setupBottomNavigation();

        // Only create/add fragments on first launch; FragmentManager restores them otherwise.
        if (savedInstanceState == null) {
            initFragments();
        } else {
            // Re-attach references after configuration change
            FragmentManager fm = getSupportFragmentManager();
            homeFragment           = (HomeFragment)           fm.findFragmentByTag("home");
            questionnairesFragment = (QuestionnairesFragment) fm.findFragmentByTag("questionnaires");
            plusFragment           = (PlusFragment)           fm.findFragmentByTag("plus");
            insightsFragment       = (InsightsFragment)       fm.findFragmentByTag("insights");
            profileFragment        = (ProfileFragment)        fm.findFragmentByTag("profile");

            // Restore active fragment from selected bottom nav item
            int selectedId = bottomNav.getSelectedItemId();
            activeFragment = fragmentForNavId(selectedId);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundSync();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
    }

    /**
     * Creates all five fragment instances and adds them to the back stack in one
     * transaction.  All but HomeFragment are hidden immediately so only the home
     * tab is visible at launch.
     */
    private void initFragments() {
        homeFragment           = new HomeFragment();
        questionnairesFragment = new QuestionnairesFragment();
        plusFragment           = new PlusFragment();
        insightsFragment       = new InsightsFragment();
        profileFragment        = new ProfileFragment();

        FragmentManager fm = getSupportFragmentManager();
        fm.beginTransaction()
                .add(R.id.fragment_container, homeFragment,           "home")
                .add(R.id.fragment_container, questionnairesFragment, "questionnaires").hide(questionnairesFragment)
                .add(R.id.fragment_container, plusFragment,           "plus").hide(plusFragment)
                .add(R.id.fragment_container, insightsFragment,       "insights").hide(insightsFragment)
                .add(R.id.fragment_container, profileFragment,        "profile").hide(profileFragment)
                .commit();

        activeFragment = homeFragment;
    }

    private void setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottom_navigation);

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_home) {
                showFragment(homeFragment, "Home");
                return true;
            } else if (id == R.id.nav_questionnaires) {
                showFragment(questionnairesFragment, "Assessments");
                return true;
            } else if (id == R.id.nav_plus) {
                showFragment(plusFragment, "Add Data");
                return true;
            } else if (id == R.id.nav_insights) {
                showFragment(insightsFragment, "Insights");
                return true;
            } else if (id == R.id.nav_profile) {
                showFragment(profileFragment, "Profile");
                return true;
            }

            return false;
        });

        // Tapping the already-selected Home tab scrolls back to top (no-op for others)
        bottomNav.setOnItemReselectedListener(item -> {
            if (item.getItemId() == R.id.nav_home && homeFragment != null) {
                homeFragment.scrollToTop();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fragment switching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows the target fragment and hides the currently active one.
     * Uses hide/show (not replace) so fragment state is preserved between tab switches.
     */
    private void showFragment(Fragment target, String toolbarTitle) {
        if (target == null || target == activeFragment) return;

        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .hide(activeFragment)
                .show(target)
                .commit();

        activeFragment = target;
        updateToolbarTitle(toolbarTitle);
    }

    private void updateToolbarTitle(String title) {
        if (toolbar == null) return;
        TextView tvTitle = toolbar.findViewById(R.id.tvToolbarTitle);
        if (tvTitle != null) {
            tvTitle.setText(title);
        }
    }

    /** Returns the fragment that corresponds to the given BottomNavigationView item id. */
    private Fragment fragmentForNavId(int navId) {
        if (navId == R.id.nav_questionnaires) return questionnairesFragment;
        if (navId == R.id.nav_plus)           return plusFragment;
        if (navId == R.id.nav_insights)       return insightsFragment;
        if (navId == R.id.nav_profile)        return profileFragment;
        return homeFragment; // default
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Back-press handling
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        // Navigate to Home tab first if the user is on any other tab
        if (activeFragment != homeFragment) {
            bottomNav.setSelectedItemId(R.id.nav_home);
            return;
        }

        // On Home tab: require a second back press within 2 s to exit
        if (backPressedOnce) {
            super.onBackPressed();
            return;
        }

        backPressedOnce = true;
        Snackbar.make(
                findViewById(android.R.id.content),
                "Press back again to exit",
                Snackbar.LENGTH_SHORT
        ).show();

        handler.postDelayed(() -> backPressedOnce = false, 2000);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Background sync
    // ─────────────────────────────────────────────────────────────────────────

    private void startBackgroundSync() {
        if (!FirebaseAuthHelper.isLoggedIn()) return;

        new Thread(() -> {
            try {
                SyncManager syncManager = new SyncManager(getApplicationContext());
                syncManager.pushAllDirtyData(new SyncManager.SyncCallback() {
                    @Override public void onSuccess() {
                        Log.d(TAG, "Background sync complete");
                    }
                    @Override public void onFailure(String error) {
                        Log.e(TAG, "Background sync failed: " + error);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Sync exception: " + e.getMessage(), e);
            }
        }).start();
    }
}
