package com.arbaba.chordifymobile.view;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.airbnb.lottie.LottieAnimationView;
import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.viewmodel.MainViewModel;

public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private TextView tvResult, tvConfidence, tvInstruction;
    private LottieAnimationView lottieWave;
    private View btnTrigger;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI Refs
        tvResult = findViewById(R.id.tvResult);
        tvConfidence = findViewById(R.id.tvConfidence);
        tvInstruction = findViewById(R.id.tvInstruction);
        lottieWave = findViewById(R.id.lottieWave);
        btnTrigger = findViewById(R.id.btnTrigger);

        // Load Animation Resource
        // Make sure to put 'wave_anim.json' in res/raw or assets
        lottieWave.setAnimation(R.raw.wave_anim);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // --- OBSERVERS ---
        viewModel.getChordText().observe(this, rawText -> {
            // rawText might be "Am (98%)" or "Error..."
            if(rawText.contains("(")) {
                String[] parts = rawText.split("\\(");
                tvResult.setText(parts[0].trim());
                tvConfidence.setText("Confidence: " + parts[1].replace(")", ""));
            } else {
                // Handling status messages
                if(rawText.contains("Listen") || rawText.contains("Analyze")) {
                    tvInstruction.setText(rawText);
                } else {
                    tvResult.setText("--");
                    tvInstruction.setText(rawText);
                }
            }
        });

        viewModel.getIsRecording().observe(this, isRecording -> {
            if (isRecording) {
                lottieWave.playAnimation();
                tvInstruction.setText("Listening...");
                btnTrigger.setEnabled(false); // Prevent double tap
            } else {
                lottieWave.pauseAnimation();
                lottieWave.setProgress(0); // Reset animation
                btnTrigger.setEnabled(true);
            }
        });

        // --- CLICK LISTENER ---
        btnTrigger.setOnClickListener(v -> {
            if (checkPermission()) {
                viewModel.startListeningLoop(getCacheDir());
            } else {
                requestPermission();
            }
        });
    }

    // Permissions (Same as before)
    private boolean checkPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
    }
}