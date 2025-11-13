package com.yadhuChoudhary.MyRuns3

import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExerciseRepository(private val exerciseDao: ExerciseDao) {

    val allExercises: LiveData<List<ExerciseEntry>> = exerciseDao.getAllExercises()

    suspend fun insert(exercise: ExerciseEntry): Long {
        return withContext(Dispatchers.IO) {
            exerciseDao.insertExercise(exercise)
        }
    }

    suspend fun getExerciseById(id: Long): ExerciseEntry? {
        return withContext(Dispatchers.IO) {
            exerciseDao.getExerciseById(id)
        }
    }

    suspend fun delete(exercise: ExerciseEntry) {
        withContext(Dispatchers.IO) {
            exerciseDao.deleteExercise(exercise)
        }
    }

    suspend fun deleteById(id: Long) {
        withContext(Dispatchers.IO) {
            exerciseDao.deleteExerciseById(id)
        }
    }

    suspend fun update(exercise: ExerciseEntry) {
        withContext(Dispatchers.IO) {
            exerciseDao.updateExercise(exercise)
        }
    }

    suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            exerciseDao.deleteAllExercises()
        }
    }
}