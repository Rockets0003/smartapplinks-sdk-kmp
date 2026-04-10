package com.smartapplinks.track

/**
 * Navigation listener for Compose Navigation.
 *
 * Usage with Jetpack Compose Navigation:
 *   val navController = rememberNavController()
 *   LaunchedEffect(navController) {
 *       navController.addOnDestinationChangedListener(TrackNavListener())
 *   }
 *
 * Usage with Voyager:
 *   Call TrackSDK.trackScreen(screen.key) in your Tab/Screen composable.
 *
 * Usage with Decompose:
 *   Call TrackSDK.trackScreen(config.name) in your navigation callback.
 */
class TrackNavListener {
    private var currentDestination: String = ""

    fun onDestinationChanged(route: String) {
        if (route == currentDestination) return
        currentDestination = route
        TrackSDK.trackScreen(route)
    }
}
