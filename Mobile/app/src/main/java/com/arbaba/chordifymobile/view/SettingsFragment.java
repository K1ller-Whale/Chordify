package com.arbaba.chordifymobile.view;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.arbaba.chordifymobile.R;
import com.arbaba.chordifymobile.api.RetrofitClient;
import com.arbaba.chordifymobile.viewmodel.MainViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsFragment extends Fragment {

    private MainViewModel viewModel;
    private TextInputEditText etApiUrl;
    private MaterialButton btnSaveApiUrl, btnClearHistory;
    private SharedPreferences prefs;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etApiUrl = view.findViewById(R.id.etApiUrl);
        btnSaveApiUrl = view.findViewById(R.id.btnSaveApiUrl);
        btnClearHistory = view.findViewById(R.id.btnClearHistory);

        prefs = requireContext().getSharedPreferences("chordify_prefs", 0);
        
        // Load saved API URL
        String savedUrl = prefs.getString("api_url", "http://192.168.1.37:8000/");
        etApiUrl.setText(savedUrl);

        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        btnSaveApiUrl.setOnClickListener(v -> {
            String url = etApiUrl.getText().toString().trim();
            if (url.isEmpty() || !url.startsWith("http")) {
                Toast.makeText(getContext(), "Please enter a valid URL", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Ensure URL ends with /
            if (!url.endsWith("/")) {
                url += "/";
            }
            
            prefs.edit().putString("api_url", url).apply();
            RetrofitClient.updateBaseUrl(url);
            Toast.makeText(getContext(), "API URL saved. Restart app to apply.", Toast.LENGTH_LONG).show();
        });

        btnClearHistory.setOnClickListener(v -> {
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
}
