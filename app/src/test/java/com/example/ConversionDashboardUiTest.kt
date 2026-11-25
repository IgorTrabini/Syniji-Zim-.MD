package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.model.ConversionEvent
import com.example.model.ConversionProgress
import com.example.model.ConversionTaskStatus
import com.example.ui.screens.ConversionScreen
import com.example.ui.theme.SynijiZimTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversionDashboardUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `dashboard renders LinearProgressIndicator and updates live as articles are parsed`() {
        val progressFlow = MutableStateFlow(
            ConversionProgress(
                taskId = "task-live-1",
                status = ConversionTaskStatus.RUNNING,
                phaseIndex = 2,
                phaseLabel = "Convertendo conteúdo textual...",
                totalEntries = 100,
                processedEntries = 25,
                totalArticles = 80,
                articlesConverted = 20,
                redirectsIdentified = 10,
                redirectsResolved = 5,
                mediaExcluded = 15,
                linksConverted = 42,
                linksInvalid = 1,
                markdownFilesCreated = 20,
                outputBytesWritten = 48_000L,
                elapsedMs = 2_000L,
                articlesPerSecond = 10.0,
                estimatedRemainingMs = 6_000L,
                currentEntryTitle = "História do Brasil",
                currentEntryPath = "A/Historia/Brasil.html",
                currentEntryMime = "text/html",
                recentEvents = listOf(ConversionEvent(1000L, "INFO", "Processando Brasil.html"))
            )
        )

        var pausedClicked = 0
        var resumeClicked = 0
        var resultOpenedId: String? = null

        composeRule.setContent {
            val state by progressFlow.collectAsState()
            SynijiZimTheme {
                ConversionScreen(
                    progress = state,
                    onPause = { pausedClicked++ },
                    onResume = { resumeClicked++ },
                    onCancel = {},
                    onViewResult = { resultOpenedId = it },
                    onBackHome = {}
                )
            }
        }

        composeRule.onNodeWithTag("conversion_progress_bar").assertIsDisplayed()
        composeRule.onNodeWithTag("conversion_percent_text").assertTextContains("25%")
        composeRule.onNodeWithTag("conversion_status_text").assertTextContains("Convertendo conteúdo textual...")
        composeRule.onNodeWithTag("conversion_current_article_text").assertTextContains("História do Brasil", substring = true)
        composeRule.onNodeWithTag("conversion_counts_text").assertTextContains("25 / 100 itens")

        composeRule.onNodeWithTag("conversion_dashboard_list")
            .performScrollToNode(hasTestTag("pause_conversion_button"))
        composeRule.onNodeWithTag("pause_conversion_button")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, pausedClicked)

        // Emit live update for a subsequent article
        progressFlow.value = progressFlow.value.copy(
            processedEntries = 60,
            articlesConverted = 55,
            currentEntryTitle = "Sistema Operacional",
            currentEntryPath = "A/Sistema_Operacional.html"
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("conversion_dashboard_list")
            .performScrollToNode(hasTestTag("conversion_percent_text"))
        composeRule.onNodeWithTag("conversion_percent_text").assertTextContains("60%")
        composeRule.onNodeWithTag("conversion_current_article_text").assertTextContains("Sistema Operacional", substring = true)
        composeRule.onNodeWithTag("conversion_counts_text").assertTextContains("60 / 100 itens")

        // Emit PAUSED state -> verify Resume button appears and triggers callback
        progressFlow.value = progressFlow.value.copy(
            status = ConversionTaskStatus.PAUSED,
            phaseLabel = "Conversão pausada em ponto de retomada"
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversion_dashboard_list")
            .performScrollToNode(hasTestTag("resume_conversion_button"))
        composeRule.onNodeWithTag("resume_conversion_button")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, resumeClicked)

        // Emit COMPLETED state -> verify Result button appears and passes taskId
        progressFlow.value = progressFlow.value.copy(
            status = ConversionTaskStatus.COMPLETED,
            phaseIndex = 3,
            phaseLabel = "Conversão concluída com sucesso",
            processedEntries = 100
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversion_dashboard_list")
            .performScrollToNode(hasTestTag("conversion_percent_text"))
        composeRule.onNodeWithTag("conversion_percent_text").assertTextContains("100%")
        composeRule.onNodeWithTag("conversion_dashboard_list")
            .performScrollToNode(hasTestTag("conversion_open_result_button"))
        composeRule.onNodeWithTag("conversion_open_result_button")
            .assertIsDisplayed()
            .performClick()
        assertEquals("task-live-1", resultOpenedId)
    }
}
