package com.arbaba.chordifymobile.view;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.model.HistoryEntry;
import com.arbaba.chordifymobile.viewmodel.MainViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChromaFragment extends Fragment {

    private MainViewModel viewModel;
    private ImageView imgChroma;
    private ProgressBar progressChroma;
    private LinearLayout tvChromaEmpty;
    private MaterialButton btnGenerateChroma;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chroma, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        imgChroma = view.findViewById(R.id.imgChroma);
        progressChroma = view.findViewById(R.id.progressChroma);
        tvChromaEmpty = view.findViewById(R.id.tvChromaEmpty);
        btnGenerateChroma = view.findViewById(R.id.btnGenerateChroma);

        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        viewModel.getChromaImageData().observe(getViewLifecycleOwner(), imageData -> {
            if (imageData != null && imageData.length > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                if (imgChroma != null && bitmap != null) {
                    imgChroma.setImageBitmap(bitmap);
                    imgChroma.setVisibility(View.VISIBLE);
                }
                if (tvChromaEmpty != null) {
                    tvChromaEmpty.setVisibility(View.GONE);
                }
            }
        });

        viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
            if (progressChroma != null) {
                progressChroma.setVisibility(isLoading ? View.VISIBLE : View.GONE);
            }
            if (btnGenerateChroma != null) {
                btnGenerateChroma.setEnabled(!isLoading);
            }
        });

        btnGenerateChroma.setOnClickListener(v -> {
            // Get all history entries with audio files
            List<HistoryEntry> allHistory = viewModel.getAllHistory().getValue();
            if (allHistory == null || allHistory.isEmpty()) {
                Toast.makeText(getContext(), "No recordings available. Please record audio first.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Filter entries that have audio file paths
            List<HistoryEntry> entriesWithAudio = new ArrayList<>();
            for (HistoryEntry entry : allHistory) {
                if (entry.getAudioFilePath() != null && !entry.getAudioFilePath().isEmpty()) {
                    File audioFile = new File(entry.getAudioFilePath());
                    if (audioFile.exists()) {
                        entriesWithAudio.add(entry);
                    }
                }
            }

            if (entriesWithAudio.isEmpty()) {
                Toast.makeText(getContext(), "No audio files found. Please record audio first.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create dialog items
            String[] items = new String[entriesWithAudio.size()];
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
            for (int i = 0; i < entriesWithAudio.size(); i++) {
                HistoryEntry entry = entriesWithAudio.get(i);
                String dateStr = sdf.format(new Date(entry.getTimestamp()));
                items[i] = entry.getChord() + " (" + String.format(Locale.getDefault(), "%.1f%%", entry.getConfidence()) + ") - " + dateStr;
            }

            // Show selection dialog
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Select Recording")
                    .setItems(items, (dialog, which) -> {
                        HistoryEntry selectedEntry = entriesWithAudio.get(which);
                        File selectedFile = new File(selectedEntry.getAudioFilePath());
                        if (selectedFile.exists()) {
                            viewModel.uploadAudioForChroma(selectedFile);
                        } else {
                            Toast.makeText(getContext(), "Audio file not found", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }
}
