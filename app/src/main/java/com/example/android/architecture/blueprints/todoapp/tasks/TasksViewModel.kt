/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.architecture.blueprints.todoapp.tasks

import android.arch.lifecycle.ViewModel
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.mvibase.MviAction
import com.example.android.architecture.blueprints.todoapp.mvibase.MviIntent
import com.example.android.architecture.blueprints.todoapp.mvibase.MviResult
import com.example.android.architecture.blueprints.todoapp.mvibase.MviView
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewModel
import com.example.android.architecture.blueprints.todoapp.mvibase.MviViewState
import com.example.android.architecture.blueprints.todoapp.tasks.TasksAction.ActivateTaskAction
import com.example.android.architecture.blueprints.todoapp.tasks.TasksAction.ClearCompletedTasksAction
import com.example.android.architecture.blueprints.todoapp.tasks.TasksAction.CompleteTaskAction
import com.example.android.architecture.blueprints.todoapp.tasks.TasksAction.LoadTasksAction
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ACTIVE_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.ALL_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksFilterType.COMPLETED_TASKS
import com.example.android.architecture.blueprints.todoapp.tasks.TasksResult.ActivateTaskResult
import com.example.android.architecture.blueprints.todoapp.tasks.TasksResult.ClearCompletedTasksResult
import com.example.android.architecture.blueprints.todoapp.tasks.TasksResult.CompleteTaskResult
import com.example.android.architecture.blueprints.todoapp.tasks.TasksResult.LoadTasksResult
import com.example.android.architecture.blueprints.todoapp.util.notOfType
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.functions.BiFunction
import io.reactivex.subjects.PublishSubject

/**
 * Listens to user actions from the UI ([TasksFragment]), retrieves the data and updates the
 * UI as required.
 *
 * @property actionProcessorHolder Contains and executes the business logic of all emitted
 * actions.
 */
class TasksViewModel(
    private val actionProcessorHolder: TasksActionProcessorHolder
) : ViewModel(), MviViewModel<TasksIntent, TasksViewState> {

  /**
   * Proxy subject used to keep the stream alive even after the UI gets recycled.
   * This is basically used to keep ongoing events and the last cached State alive
   * while the UI disconnects and reconnects on config changes.
   */
  private val intentsSubject: PublishSubject<TasksIntent> = PublishSubject.create()
  private val statesObservable: Observable<TasksViewState> = compose()

  /**
   * take only the first ever InitialIntent and all intents of other types
   * to avoid reloading data on config changes
   */
  private val intentFilter: ObservableTransformer<TasksIntent, TasksIntent>
    get() = ObservableTransformer { intents ->
      intents.publish { shared ->
        Observable.merge(
            shared.ofType(TasksIntent.InitialIntent::class.java).take(1),
            shared.notOfType(TasksIntent.InitialIntent::class.java)
        )
      }
    }

  override fun processIntents(intents: Observable<TasksIntent>) {
    intents.subscribe(intentsSubject)
  }

  override fun states(): Observable<TasksViewState> = statesObservable

  /**
   * Compose all components to create the stream logic
   */
  private fun compose(): Observable<TasksViewState> {
    return intentsSubject
        .compose(intentFilter)
        .map(this::actionFromIntent)
        .compose(actionProcessorHolder.actionProcessor)
        // Cache each state and pass it to the reducer to create a new state from
        // the previous cached one and the latest Result emitted from the action processor.
        // The Scan operator is used here for the caching.
        .scan(TasksViewState.idle(), reducer)
        // Emit the last one event of the stream on subscription
        // Useful when a View rebinds to the ViewModel after rotation.
        .replay(1)
        // Create the stream on creation without waiting for anyone to subscribe
        // This allows the stream to stay alive even when the UI disconnects and
        // match the stream's lifecycle to the ViewModel's one.
        .autoConnect(0)
  }

  /**
   * Translate an [MviIntent] to an [MviAction].
   * Used to decouple the UI and the business logic to allow easy testings and reusability.
   */
  private fun actionFromIntent(intent: TasksIntent): TasksAction {
    return when (intent) {
      is TasksIntent.InitialIntent -> LoadTasksAction(true, TasksFilterType.ALL_TASKS)
      is TasksIntent.RefreshIntent -> LoadTasksAction(intent.forceUpdate, null)
      is TasksIntent.ActivateTaskIntent -> ActivateTaskAction(intent.task)
      is TasksIntent.CompleteTaskIntent -> CompleteTaskAction(intent.task)
      is TasksIntent.ClearCompletedTasksIntent -> ClearCompletedTasksAction
      is TasksIntent.ChangeFilterIntent -> LoadTasksAction(false, intent.filterType)
    }
  }

  companion object {
    /**
     * The Reducer is where [MviViewState], that the [MviView] will use to
     * render itself, are created.
     * It takes the last cached [MviViewState], the latest [MviResult] and
     * creates a new [MviViewState] by only updating the related fields.
     * This is basically like a big switch statement of all possible types for the [MviResult]
     */
    private val reducer = BiFunction { previousState: TasksViewState, result: TasksResult ->
      when (result) {
        is LoadTasksResult -> when (result) {
          is LoadTasksResult.Success -> {
            val filterType = result.filterType ?: previousState.tasksFilterType
            val tasks = filteredTasks(result.tasks, filterType)
            previousState.copy(
                isLoading = false,
                tasks = tasks,
                tasksFilterType = filterType
            )
          }
          is LoadTasksResult.Failure -> previousState.copy(isLoading = false, error = result.error)
          is LoadTasksResult.InFlight -> previousState.copy(isLoading = true)
        }
        is CompleteTaskResult -> when (result) {
          is CompleteTaskResult.Success ->
            previousState.copy(
                taskComplete = true,
                tasks = filteredTasks(result.tasks, previousState.tasksFilterType)
            )
          is CompleteTaskResult.Failure -> previousState.copy(error = result.error)
          is CompleteTaskResult.InFlight -> previousState
          is CompleteTaskResult.HideUiNotification -> previousState.copy(taskComplete = false)
        }
        is ActivateTaskResult -> when (result) {
          is ActivateTaskResult.Success ->
            previousState.copy(
                taskActivated = true,
                tasks = filteredTasks(result.tasks, previousState.tasksFilterType)
            )
          is ActivateTaskResult.Failure -> previousState.copy(error = result.error)
          is ActivateTaskResult.InFlight -> previousState
          is ActivateTaskResult.HideUiNotification -> previousState.copy(taskActivated = false)
        }
        is ClearCompletedTasksResult -> when (result) {
          is ClearCompletedTasksResult.Success ->
            previousState.copy(
                completedTasksCleared = true,
                tasks = filteredTasks(result.tasks, previousState.tasksFilterType)
            )
          is ClearCompletedTasksResult.Failure -> previousState.copy(error = result.error)
          is ClearCompletedTasksResult.InFlight -> previousState
          is ClearCompletedTasksResult.HideUiNotification ->
            previousState.copy(completedTasksCleared = false)
        }
      }
    }

    private fun filteredTasks(tasks: List<Task>, filterType: TasksFilterType): List<Task> {
      return when (filterType) {
        ALL_TASKS -> tasks
        ACTIVE_TASKS -> tasks.filter(Task::active)
        COMPLETED_TASKS -> tasks.filter(Task::completed)
      }
    }
  }
}
