package com.yulin.rider.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun taskIdSurvivesComposeNavigation() {
        compose.setContent {
            val controller = rememberNavController()
            NavHost(controller, startDestination = RiderRoutes.HOME) {
                composable(RiderRoutes.HOME) {
                    Button(
                        onClick = { controller.navigate(RiderRoutes.taskDetail(42)) },
                        modifier = Modifier.testTag("open-task"),
                    ) {
                        Text("打开")
                    }
                }
                composable(
                    route = RiderRoutes.TASK_DETAIL,
                    arguments = listOf(
                        navArgument(RiderRoutes.ARG_TASK_ID) { type = NavType.LongType }
                    ),
                ) { entry ->
                    Text(
                        text = entry.arguments?.getLong(RiderRoutes.ARG_TASK_ID).toString(),
                        modifier = Modifier.testTag("task-id"),
                    )
                }
            }
        }

        compose.onNodeWithTag("open-task").performClick()
        compose.onNodeWithTag("task-id").assertTextEquals("42")
    }
}
