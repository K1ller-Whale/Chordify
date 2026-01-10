package com.arbaba.chordifymobile.view;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.model.HistoryEntry;
import com.arbaba.chordifymobile.utils.AudioPlayerHelper;
import com.arbaba.chordifymobile.viewmodel.MainViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.ImageButton;

import java.io.File;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryFragment extends Fragment {

    private MainViewModel viewModel;
    private RecyclerView recyclerView;
    private HistoryAdapter adapter;
    private View emptyState;
    private MaterialButton btnClearAll;
    private AudioPlayerHelper audioPlayerHelper;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.recyclerViewHistory);
        emptyState = view.findViewById(R.id.emptyState);
        btnClearAll = view.findViewById(R.id.btnClearAll);

        audioPlayerHelper = new AudioPlayerHelper();
        adapter = new HistoryAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        viewModel.getAllHistory().observe(getViewLifecycleOwner(), entries -> {
            adapter.setEntries(entries);
            if (entries == null || entries.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                emptyState.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            }
        });
        
        // Initially hide empty state until we know if there's data
        emptyState.setVisibility(View.GONE);

        btnClearAll.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Clear All History")
                    .setMessage("Are you sure you want to delete all detection history?")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        viewModel.clearAllHistory();
                        Toast.makeText(getContext(), "History cleared", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (audioPlayerHelper != null) {
            audioPlayerHelper.stopAudio();
        }
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {
        private List<HistoryEntry> entries;

        public void setEntries(List<HistoryEntry> entries) {
            this.entries = entries;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            HistoryEntry entry = entries.get(position);
            holder.tvChord.setText(entry.getChord());
            holder.tvConfidence.setText(String.format(Locale.getDefault(), "%.1f%%", entry.getConfidence()));
            
            // Format timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());
            String timeStr = sdf.format(new Date(entry.getTimestamp()));
            holder.tvTime.setText(timeStr);

            holder.btnPlay.setOnClickListener(v -> {
                if (entry.getAudioFilePath() != null) {
                    File audioFile = new File(entry.getAudioFilePath());
                    if (audioFile.exists()) {
                        if (audioPlayerHelper.isPlaying()) {
                            audioPlayerHelper.stopAudio();
                            holder.btnPlay.setImageResource(android.R.drawable.ic_media_play);
                        } else {
                            audioPlayerHelper.playAudio(audioFile, new AudioPlayerHelper.OnPlaybackCompleteListener() {
                                @Override
                                public void onComplete() {
                                    holder.btnPlay.setImageResource(android.R.drawable.ic_media_play);
                                }

                                @Override
                                public void onError(String error) {
                                    Toast.makeText(getContext(), "Playback error: " + error, Toast.LENGTH_SHORT).show();
                                    holder.btnPlay.setImageResource(android.R.drawable.ic_media_play);
                                }
                            });
                            holder.btnPlay.setImageResource(android.R.drawable.ic_media_pause);
                        }
                    } else {
                        Toast.makeText(getContext(), "Audio file not found", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(getContext(), "No audio file available", Toast.LENGTH_SHORT).show();
                }
            });

            holder.btnDelete.setOnClickListener(v -> {
                viewModel.deleteHistoryEntry(entry);
                Toast.makeText(getContext(), "Deleted", Toast.LENGTH_SHORT).show();
            });
        }

        @Override
        public int getItemCount() {
            return entries == null ? 0 : entries.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvChord, tvConfidence, tvTime;
            ImageButton btnPlay, btnDelete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvChord = itemView.findViewById(R.id.tvHistoryChord);
                tvConfidence = itemView.findViewById(R.id.tvHistoryConfidence);
                tvTime = itemView.findViewById(R.id.tvHistoryTime);
                btnPlay = itemView.findViewById(R.id.btnPlay);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}
