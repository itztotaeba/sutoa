package com.kinyo.sutoa.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kinyo.sutoa.ui.components.BottomNavBar
import com.kinyo.sutoa.ui.detail.DetailScreen
import com.kinyo.sutoa.ui.home.HomeScreen
import com.kinyo.sutoa.ui.search.SearchScreen
import com.kinyo.sutoa.ui.updates.UpdatesScreen

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val UPDATES = "updates"
    const val DETAIL = "detail"

    fun detail(packageName: String) = "$DETAIL/${UriEncode.encode(packageName)}"

    const val DETAIL_ARG = "pkg"
    const val DETAIL_ROUTE = "$DETAIL/{$DETAIL_ARG}"
}

/** Package names contain dots — safe either way, but encode to be robust. */
object UriEncode {
    fun encode(value: String): String = android.net.Uri.encode(value)
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startPackage: String? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            BottomNavBar(
                navController = navController,
                currentRoute = currentRoute,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Deep link from an update notification.
            LaunchedEffect(startPackage) {
                if (!startPackage.isNullOrBlank()) {
                    navController.navigate(Routes.detail(startPackage))
                }
            }

            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onAppClick = { pkg -> navController.navigate(Routes.detail(pkg)) },
                        onSearchClick = { navController.navigate(Routes.SEARCH) },
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        onAppClick = { pkg -> navController.navigate(Routes.detail(pkg)) },
                    )
                }
                composable(Routes.UPDATES) {
                    UpdatesScreen(
                        onAppClick = { pkg -> navController.navigate(Routes.detail(pkg)) },
                    )
                }
                composable(
                    route = Routes.DETAIL_ROUTE,
                    arguments = listOf(navArgument(Routes.DETAIL_ARG) { type = NavType.StringType }),
                ) { entry ->
                    val pkg = entry.arguments?.getString(Routes.DETAIL_ARG)
                        ?.let { android.net.Uri.decode(it) }
                    if (pkg != null) {
                        DetailScreen(
                            packageName = pkg,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
