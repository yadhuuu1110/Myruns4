package com.yadhuChoudhary.MyRuns3

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class ExerciseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ExerciseRepository
    val allExercises: LiveData<List<ExerciseEntry>>

    init {
        val exerciseDao = ExerciseDatabase.getDatabase(application).exerciseDao()
        repository = ExerciseRepository(exerciseDao)
        allExercises = repository.getAllExercisesLiveData()
    }

    fun insert(exercise: ExerciseEntry) = viewModelScope.launch {
        repository.insert(exercise)
    }

    fun delete(exercise: ExerciseEntry) = viewModelScope.launch {
        repository.delete(exercise)
    }

    fun deleteAll() = viewModelScope.launch {
        repository.deleteAll()
    }

    fun update(exercise: ExerciseEntry) = viewModelScope.launch {
        repository.update(exercise)
    }
}