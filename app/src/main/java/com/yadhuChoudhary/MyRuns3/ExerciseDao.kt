package com.yadhuChoudhary.MyRuns3

import androidx.room.*

@Dao
interface ExerciseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exercise: ExerciseEntry): Long

    @Query("SELECT * FROM exercise_table ORDER BY dateTime DESC")
    suspend fun getAllExercises(): List<ExerciseEntry>

    @Query("SELECT * FROM exercise_table WHERE id = :id")
    suspend fun getExerciseById(id: Long): ExerciseEntry?

    @Delete
    suspend fun delete(exercise: ExerciseEntry)

    @Update
    suspend fun update(exercise: ExerciseEntry)

    // NEW: Delete all records
    @Query("DELETE FROM exercise_table")
    suspend fun deleteAll()
}