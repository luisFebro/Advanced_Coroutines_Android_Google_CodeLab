/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.advancedcoroutines

import androidx.lifecycle.*
import com.example.android.advancedcoroutines.utils.ComparablePair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * The [ViewModel] for fetching a list of [Plant]s.
 */
class PlantListViewModel internal constructor(
    private val plantRepository: PlantRepository
) : ViewModel() {

    /**
     * Request a snackbar to display a string.
     *
     * This variable is private because we don't want to expose [MutableLiveData].
     *
     * MutableLiveData allows anyone to set a value, and [PlantListViewModel] is the only
     * class that should be setting values.
     */
    private val _snackbar = MutableLiveData<String?>()

    /**
     * Request a snackbar to display a string.
     */
    val snackbar: LiveData<String?>
        get() = _snackbar

    private val _spinner = MutableLiveData<Boolean>(false)
    /**
     * Show a loading spinner if true
     */
    val spinner: LiveData<Boolean>
        get() = _spinner

    /**
     * The current growZone selection.
     */
    private val growZone = MutableLiveData<GrowZone>(NoGrowZone)

    /**
     * A list of plants that updates based on the current filter.
     */
    val plants: LiveData<List<Plant>> = growZone.switchMap { growZone ->
        if (growZone == NoGrowZone) {
            plantRepository.plants
        } else {
            plantRepository.getPlantsWithGrowZone(growZone)
        }
    }

    // Since we want to keep LiveData in the UI layer for this codelab, we'll use the asLiveData extension function to convert our Flow into a LiveData. Just like the LiveData builder, this adds a configurable timeout to the LiveData generated. This is nice because it keeps us from restarting our query every time the configuration changes (such as from device rotation).
    // StateFlow is different from a regular flow created using, for example, the flow{} builder. A StateFlow is created with an initial value and keeps its state even without being collected and between subsequent collections. You can use the MutableStateFlow interface (as shown above) to change the value (state) of a StateFlow.
    //You will often find that flows that behave like StateFlow are called hot, as opposed to regular, cold flows which only execute when they're collected.
    // ref: https://developer.android.com/codelabs/advanced-kotlin-coroutines#11
    private val growZoneFlow = MutableStateFlow<GrowZone>(NoGrowZone)

    @OptIn(ExperimentalCoroutinesApi::class)
    // This pattern shows how to integrate events (grow zone changing) into a flow. It does exactly the same thing as the LiveData.switchMap version–switching between two data sources based on an event.
    // Inside the flatMapLatest, we switch based on the growZone. This code is pretty much the same as the LiveData.switchMap version, with the only difference being that it returns Flows instead of LiveDatas.
    val plantsUsingFlow: LiveData<List<Plant>> = growZoneFlow.flatMapLatest { growZone ->
        if (growZone == NoGrowZone) {
            plantRepository.plantsFlow
        } else {
            plantRepository.getPlantsWithGrowZoneFlow(growZone)
        }
    }.asLiveData()

    init {
        // When creating a new ViewModel, clear the grow zone and perform any related udpates
        clearGrowZoneNumber()

        // fetch the full plant list
        //launchDataLoad { plantRepository.tryUpdateRecentPlantsCache() }

        // We can use mapLatest to control concurrency for us. Instead of building cancel/restart logic ourselves, the flow transform can take care of it. This code saves a lot of code and complexity compared to writing the same cancellation logic by hand.
        growZoneFlow.mapLatest { growZone ->
            _spinner.value = true
            if (growZone == NoGrowZone) {
                plantRepository.tryUpdateRecentPlantsCache()
            } else {
                plantRepository.tryUpdateRecentPlantsForGrowZoneCache(growZone)
            }
        }
            .onEach { _spinner.value = false }
            .catch { throwable ->  _snackbar.value = throwable.message  }
            .launchIn(viewModelScope)
    }

    /**
     * Filter the list to this grow zone.
     *
     * In the starter code version, this will also start a network request. After refactoring,
     * updating the grow zone will automatically kickoff a network request.
     */
    fun setGrowZoneNumber(num: Int) {
        growZone.value = GrowZone(num)
        growZoneFlow.value = GrowZone(num)

        // initial code version, will move during flow rewrite
        // launchDataLoad { plantRepository.tryUpdateRecentPlantsCache() }
    }

    /**
     * Clear the current filter of this plants list.
     *
     * In the starter code version, this will also start a network request. After refactoring,
     * updating the grow zone will automatically kickoff a network request.
     */
    fun clearGrowZoneNumber() {
        growZone.value = NoGrowZone
        growZoneFlow.value = NoGrowZone

        // initial code version, will move during flow rewrite
        // launchDataLoad { plantRepository.tryUpdateRecentPlantsCache() }
    }

    /**
     * Return true iff the current list is filtered.
     */
    fun isFiltered() = growZone.value != NoGrowZone

    /**
     * Called immediately after the UI shows the snackbar.
     */
    fun onSnackbarShown() {
        _snackbar.value = null
    }

    /**
     * Helper function to call a data load function with a loading spinner; errors will trigger a
     * snackbar.
     *
     * By marking [block] as [suspend] this creates a suspend lambda which can call suspend
     * functions.
     *
     * @param block lambda to actually load data. It is called in the viewModelScope. Before calling
     *              the lambda, the loading spinner will display. After completion or error, the
     *              loading spinner will stop.
     */
    private fun launchDataLoad(block: suspend () -> Unit): Job {
        return viewModelScope.launch {
            try {
                _spinner.value = true
                block()
            } catch (error: Throwable) {
                _snackbar.value = error.message
            } finally {
                _spinner.value = false
            }
        }
    }
}
