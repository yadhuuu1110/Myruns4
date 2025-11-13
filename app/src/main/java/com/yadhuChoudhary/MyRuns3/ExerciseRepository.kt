package com.yadhuChoudhary.MyRuns3

import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExerciseRepository(private val exerciseDao: ExerciseDao) {

    suspend fun insert(exercise: ExerciseEntry) = withContext(Dispatchers.IO) {
        exerciseDao.insert(exercise)
    }

    suspend fun getAllExercises(): List<ExerciseEntry> = withContext(Dispatchers.IO) {
        exerciseDao.getAllExercises()
    }

    // LiveData version for ViewModel
    fun getAllExercisesLiveData(): LiveData<List<ExerciseEntry>> {
        return liveData(Dispatchers.IO) {
            val exercises = exerciseDao.getAllExercises()
            emit(exercises)
        }
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