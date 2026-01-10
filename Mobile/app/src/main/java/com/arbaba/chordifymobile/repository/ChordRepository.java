package com.arbaba.chordifymobile.repository;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import com.arbaba.chordifymobile.database.HistoryDao;
import com.arbaba.chordifymobile.database.HistoryDatabase;
import com.arbaba.chordifymobile.model.HistoryEntry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChordRepository {
    private HistoryDao historyDao;
    private LiveData<List<HistoryEntry>> allHistory;
    private ExecutorService executorService;

    public ChordRepository(Application application) {
        HistoryDatabase database = HistoryDatabase.getDatabase(application);
        historyDao = database.historyDao();
        allHistory = historyDao.getAllHistory();
        executorService = Executors.newSingleThreadExecutor();
    }

    public LiveData<List<HistoryEntry>> getAllHistory() {
        return allHistory;
    }

    public LiveData<List<HistoryEntry>> getRecentHistory(int limit) {
        return historyDao.getRecentHistory(limit);
    }

    public void insert(HistoryEntry entry) {
        executorService.execute(() -> {
            historyDao.insert(entry);
        });
    }

    public void delete(HistoryEntry entry) {
        executorService.execute(() -> {
            historyDao.delete(entry);
        });
    }

    public void deleteAll() {
        executorService.execute(() -> {
            historyDao.deleteAll();
        });
    }
}
