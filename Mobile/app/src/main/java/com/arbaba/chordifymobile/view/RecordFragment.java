package com.arbaba.chordifymobile.view;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.airbnb.lottie.LottieAnimationView;
import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.model.TimeStamp;
import com.arbaba.chordifymobile.model.TimeStampedChordResponse;
import com.arbaba.chordifymobile.model.TimeStampedChordSegment;
import com.arbaba.chordifymobile.viewmodel.MainViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RecordFragment extends Fragment {

    private MainViewModel viewModel;
    private TextView tvResult, tvConfidence, tvInstruction, tvDuration, tvError;
    private LottieAnimationView lottieWave;
    private FloatingActionButton fabRecord, fabStop;
    private ProgressBar progressBar;
    private MaterialButton btnTimeStamped;
    private MaterialCardView cardTimeStampedResults;
    private RecyclerView recyclerViewTimeStamped;
    private TimeStampedAdapter adapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_record, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI Refs
        tvResult = view.findViewById(R.id.tvResult);
        tvConfidence = view.findViewById(R.id.tvConfidence);
        tvInstruction = view.findViewById(R.id.tvInstruction);
        tvDuration = view.findViewById(R.id.tvDuration);
        tvError = view.findViewById(R.id.tvError);
        lottieWave = view.findViewById(R.id.lottieWave);
        fabRecord = view.findViewById(R.id.fabRecord);
        fabStop = view.findViewById(R.id.fabStop);
        progressBar = view.findViewById(R.id.progressBar);
        btnTimeStamped = view.findViewById(R.id.btnTimeStamped);
        cardTimeStampedResults = view.findViewById(R.id.cardTimeStampedResults);
        recyclerViewTimeStamped = view.findViewById(R.id.recyclerViewTimeStamped);
        
        adapter = new TimeStampedAdapter();
        recyclerViewTimeStamped.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewTimeStamped.setAdapter(adapter);

        // Load Animation
        lottieWave.setAnimation(R.raw.wave_anim);
        
        // Initial button state
        fabRecord.setScaleX(1f);
        fabRecord.setScaleY(1f);
        fabRecord.setAlpha(1f);

        // ViewModel
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        // Observers
        viewModel.getChordText().observe(getViewLifecycleOwner(), text -> {
            if (text.contains("(")) {
                String[] parts = text.split("\\(");
                tvResult.setText(parts[0].trim());
                tvConfidence.setText("Confidence: " + parts[1].replace(")", ""));
            } else {
                if (text.contains("Record") || text.contains("Listening") || text.contains("Analyzing") || text.contains("Processing")) {
                    tvInstruction.setText(text);
                } else {
                    tvResult.setText("--");
                    tvConfidence.setText("Confidence: --");
                    tvInstruction.setText(text);
                }
            }
        });

        viewModel.getIsRecording().observe(getViewLifecycleOwner(), isRecording -> {
            if (isRecording) {
                if (lottieWave != null) {
                    lottieWave.playAnimation();
                }
                if (tvDuration != null) {
                    tvDuration.setVisibility(View.VISIBLE);
                }
                if (fabRecord != null) {
                    fabRecord.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction(() -> {
                                if (fabRecord != null) {
                                    fabRecord.setVisibility(View.GONE);
                                }
                            });
                }
                if (fabStop != null) {
                    fabStop.setVisibility(View.VISIBLE);
                    fabStop.setScaleX(0f);
                    fabStop.setScaleY(0f);
                    fabStop.setAlpha(0f);
                    fabStop.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(200);
                }
            } else {
                if (lottieWave != null) {
                    lottieWave.pauseAnimation();
                    lottieWave.setProgress(0);
                }
                if (tvDuration != null) {
                    tvDuration.setVisibility(View.GONE);
                }
                if (fabStop != null) {
                    fabStop.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction(() -> {
                                if (fabStop != null) {
                                    fabStop.setVisibility(View.GONE);
                                }
                                if (fabRecord != null) {
                                    fabRecord.setVisibility(View.VISIBLE);
                                    fabRecord.setScaleX(0f);
                                    fabRecord.setScaleY(0f);
                                    fabRecord.setAlpha(0f);
                                    fabRecord.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .alpha(1f)
                                            .setDuration(200);
                                }
                            });
                }
            }
        });

        viewModel.getRecordingDuration().observe(getViewLifecycleOwner(), seconds -> {
            int mins = seconds / 60;
            int secs = seconds % 60;
            tvDuration.setText(String.format("%02d:%02d", mins, secs));
        });

        viewModel.getIsLoading().observe(getViewLifecycleOwner(), isLoading -> {
            progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        });

        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) {
                tvError.setText(error);
                tvError.setVisibility(View.VISIBLE);
                Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
            } else {
                tvError.setVisibility(View.GONE);
            }
        });

        viewModel.getRecordedAudioFile().observe(getViewLifecycleOwner(), file -> {
            btnTimeStamped.setVisibility(file != null ? View.VISIBLE : View.GONE);
        });

        viewModel.getTimeStampedResults().observe(getViewLifecycleOwner(), response -> {
            if (response != null && response.getSegments() != null && !response.getSegments().isEmpty()) {
                adapter.setSegments(response.getSegments());
                cardTimeStampedResults.setVisibility(View.VISIBLE);
            }
        });

        // Click Listeners
        fabRecord.setOnClickListener(v -> {
            if (checkPermission()) {
                viewModel.startRecording(requireContext().getCacheDir());
            } else {
                requestPermission();
            }
        });

        fabStop.setOnClickListener(v -> {
            viewModel.stopRecording();
        });

        btnTimeStamped.setOnClickListener(v -> {
            showTimeStampedDialog();
        });
    }

    private void showTimeStampedDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle("Analyze Time Segments");

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);

        TextInputLayout startLayout = new TextInputLayout(requireContext());
        startLayout.setHint("Start time (seconds)");
        TextInputEditText startEdit = new TextInputEditText(startLayout.getContext());
        startEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        startLayout.addView(startEdit);
        layout.addView(startLayout);

        TextInputLayout endLayout = new TextInputLayout(requireContext());
        endLayout.setHint("End time (seconds)");
        TextInputEditText endEdit = new TextInputEditText(endLayout.getContext());
        endEdit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        endLayout.addView(endEdit);
        layout.addView(endLayout);

        builder.setView(layout);
        builder.setPositiveButton("Analyze", (dialog, which) -> {
            try {
                String startStr = startEdit.getText().toString();
                String endStr = endEdit.getText().toString();
                
                if (startStr.isEmpty() || endStr.isEmpty()) {
                    Toast.makeText(getContext(), "Please enter both start and end times", Toast.LENGTH_SHORT).show();
                    return;
                }

                float start = Float.parseFloat(startStr);
                float end = Float.parseFloat(endStr);

                if (end <= start) {
                    Toast.makeText(getContext(), "End time must be greater than start time", Toast.LENGTH_SHORT).show();
                    return;
                }

                File audioFile = viewModel.getRecordedAudioFile().getValue();
                if (audioFile == null || !audioFile.exists()) {
                    Toast.makeText(getContext(), "No audio file available", Toast.LENGTH_SHORT).show();
                    return;
                }

                List<TimeStamp> timestamps = new ArrayList<>();
                timestamps.add(new TimeStamp(String.valueOf(start), String.valueOf(end)));
                viewModel.uploadAudioForTimeStamps(audioFile, timestamps);

            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "Invalid number format", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private class TimeStampedAdapter extends RecyclerView.Adapter<TimeStampedAdapter.ViewHolder> {
        private List<TimeStampedChordSegment> segments = new ArrayList<>();

        public void setSegments(List<TimeStampedChordSegment> segments) {
            this.segments = segments;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_time_stamped, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TimeStampedChordSegment segment = segments.get(position);
            holder.tvTimeRange.setText(String.format(Locale.getDefault(), "%ss - %ss", segment.getStart(), segment.getEnd()));
            holder.tvChord.setText(segment.getChord());
            holder.tvConfidence.setText(String.format(Locale.getDefault(), "%.1f%%", segment.getConfidence()));
        }

        @Override
        public int getItemCount() {
            return segments.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTimeRange, tvChord, tvConfidence;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvTimeRange = itemView.findViewById(R.id.tvTimeRange);
                tvChord = itemView.findViewById(R.id.tvTimeStampedChord);
                tvConfidence = itemView.findViewById(R.id.tvTimeStampedConfidence);
            }
        }
    }

    private boolean checkPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.RECORD_AUDIO}, 200);
    }
}
