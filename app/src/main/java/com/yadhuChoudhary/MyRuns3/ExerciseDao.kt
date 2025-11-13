package com.yadhuChoudhary.MyRuns3

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ExerciseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercise(exercise: ExerciseEntry): Long

    @Query("SELECT * FROM exercise_table ORDER BY dateTime DESC")
    fun getAllExercises(): LiveData<List<ExerciseEntry>>

    @Query("SELECT * FROM exercise_table WHERE id = :id")
    suspend fun getExerciseById(id: Long): ExerciseEntry?

    @Delete
    suspend fun deleteExercise(exercise: ExerciseEntry)

    @Query("DELETE FROM exercise_table WHERE id = :id")
    suspend fun deleteExerciseById(id: Long)

    @Update
    suspend fun updateExercise(exercise: ExerciseEntry)

    @Query("DELETE FROM exercise_table")
    suspend fun deleteAllExercises()
}