package com.example.budgetapp.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ExpensePlanDao {

    @Query("SELECT * FROM expense_plans WHERE monthKey = :monthKey ORDER BY priority ASC, id ASC")
    LiveData<List<ExpensePlan>> observeByMonth(String monthKey);

    @Query("SELECT * FROM expense_plans WHERE monthKey = :monthKey ORDER BY priority ASC, id ASC")
    List<ExpensePlan> getByMonthSync(String monthKey);

    @Query("SELECT * FROM expense_plans ORDER BY monthKey ASC, priority ASC, id ASC")
    List<ExpensePlan> getAllSync();

    @Query("SELECT * FROM expense_plans WHERE id = :id")
    ExpensePlan getByIdSync(int id);

    @Query("SELECT COUNT(*) FROM expense_plans")
    int countSync();

    @Insert
    long insert(ExpensePlan plan);

    @Update
    void update(ExpensePlan plan);

    @Delete
    void delete(ExpensePlan plan);

    @Query("DELETE FROM expense_plans")
    void deleteAll();
}
