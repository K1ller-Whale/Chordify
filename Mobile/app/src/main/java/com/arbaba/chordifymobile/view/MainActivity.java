package com.arbaba.chordifymobile.view;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.viewmodel.MainViewModel;

public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private TextView tvChord;
    private Button btnListen;
    private ProgressBar progressBar;

    private static final int PERMISSION_REQUEST_CODE = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Initialize Views
        tvChord = findViewById(R.id.tvChord);
        btnListen = findViewById(R.id.btnListen);
        progressBar = findViewById(R.id.progressBar);

        // 2. Initialize ViewModel
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        // 3. Observe Data (This updates the UI automatically)
        viewModel.getChordText().observe(this, text -> {
            tvChord.setText(text);
        });

        viewModel.getIsRecording().observe(this, isBusy -> {
            if (isBusy) {
                btnListen.setEnabled(false);
                btnListen.setText("Recording...");
                progressBar.setVisibility(View.VISIBLE);
            } else {
                btnListen.setEnabled(true);
                btnListen.setText("Listen");
                progressBar.setVisibility(View.INVISIBLE);
            }
        });

        // 4. Button Click Listener
        btnListen.setOnClickListener(v -> {
            if (checkPermission()) {
                // Pass the app's cache directory to save the temp audio file
                viewModel.startListeningLoop(getCacheDir());
            } else {
                requestPermission();
            }
        });
    }

    // --- Permissions Logic ---

    private boolean checkPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}