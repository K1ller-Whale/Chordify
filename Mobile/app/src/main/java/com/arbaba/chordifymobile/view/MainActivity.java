package com.arbaba.chordifymobile.view;

import android.content.res.ColorStateList;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.api.RetrofitClient;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TabLayout tabLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Load API URL from preferences
        RetrofitClient.loadBaseUrlFromPreferences(this);

        viewPager = findViewById(R.id.viewPager);
        tabLayout = findViewById(R.id.tabLayout);

        // Create adapter
        FragmentAdapter adapter = new FragmentAdapter(this);
        viewPager.setAdapter(adapter);

        // Connect TabLayout with ViewPager2
        TabLayoutMediator mediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Record");
                    tab.setIcon(android.R.drawable.ic_btn_speak_now);
                    break;
                case 1:
                    tab.setText("History");
                    tab.setIcon(android.R.drawable.ic_menu_recent_history);
                    break;
                case 2:
                    tab.setText("Chroma");
                    tab.setIcon(android.R.drawable.ic_menu_gallery);
                    break;
                case 3:
                    tab.setText("Settings");
                    tab.setIcon(android.R.drawable.ic_menu_preferences);
                    break;
            }
        });
        mediator.attach();

        // Set up tab icon colors using ColorStateList
        int primaryColor = ContextCompat.getColor(this, R.color.primary);
        int unselectedColor = ContextCompat.getColor(this, R.color.on_surface_variant);
        
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_selected},
                new int[]{-android.R.attr.state_selected}
        };
        int[] colors = new int[]{primaryColor, unselectedColor};
        ColorStateList iconColorStateList = new ColorStateList(states, colors);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getIcon() != null) {
                    tab.getIcon().setTintList(iconColorStateList);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
                if (tab.getIcon() != null) {
                    tab.getIcon().setTintList(iconColorStateList);
                }
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // No action needed
            }
        });

        // Set initial icon colors
        for (int i = 0; i < tabLayout.getTabCount(); i++) {
            TabLayout.Tab tab = tabLayout.getTabAt(i);
            if (tab != null && tab.getIcon() != null) {
                tab.getIcon().setTintList(iconColorStateList);
            }
        }
    }

    private static class FragmentAdapter extends FragmentStateAdapter {
        public FragmentAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    return new RecordFragment();
                case 1:
                    return new HistoryFragment();
                case 2:
                    return new ChromaFragment();
                case 3:
                    return new SettingsFragment();
                default:
                    return new RecordFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 4;
        }
    }
}
