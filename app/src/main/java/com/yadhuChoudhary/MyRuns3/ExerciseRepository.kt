package com.yadhuChoudhary.MyRuns3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExerciseRepository(private val exerciseDao: ExerciseDao) {

    suspend fun insert(exercise: ExerciseEntry) = withContext(Dispatchers.IO) {
        exerciseDao.insert(exercise)
    }

    suspend fun getAllExercises(): List<ExerciseEntry> = withContext(Dispatchers.IO) {
        exerciseDao.getAllExercises()
    }

    suspend fun getExerciseById(id: Long): ExerciseEntry? = withContext(Dispatchers.IO) {
        exerciseDao.getExerciseById(id)
    }

    suspend fun delete(exercise: ExerciseEntry) = withContext(Dispatchers.IO) {
        exerciseDao.delete(exercise)
    }

    suspend fun update(exercise: ExerciseEntry) = withContext(Dispatchers.IO) {
        exerciseDao.update(exercise)
    }

    // NEW: Delete all records
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        exerciseDao.deleteAll()
    }
}