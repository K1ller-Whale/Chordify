package com.arbaba.chordifymobile.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import com.arbaba.chordifymobile.model.HistoryEntry;
import java.util.List;

@Dao
public interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    LiveData<List<HistoryEntry>> getAllHistory();

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    LiveData<List<HistoryEntry>> getRecentHistory(int limit);

    @Insert
    void insert(HistoryEntry entry);

    @Delete
    void delete(HistoryEntry entry);

    @Query("DELETE FROM history")
    void deleteAll();
}
