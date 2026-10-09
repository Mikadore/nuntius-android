package dev.mikadore.nuntius

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SidebarViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val selectedRoute: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SELECTED_ROUTE,
        initialValue = AppDestination.HOME.route,
    )

    fun onDestinationSelected(destination: AppDestination) {
        savedStateHandle[SELECTED_ROUTE] = destination.route
    }

    fun onRouteChanged(route: String?) {
        if (AppDestination.fromRoute(route) != null) {
            savedStateHandle[SELECTED_ROUTE] = route
        }
    }

    private companion object {
        const val SELECTED_ROUTE = "sidebar_selected_route"
    }
}
