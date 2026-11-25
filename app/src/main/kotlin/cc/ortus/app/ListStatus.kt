package cc.ortus.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * What a list says above its rows: that it could not be loaded (with a
 * way to try again), or that there is nothing in it. A failure never
 * looks like an empty list.
 */
@Composable
fun ListStatus(failure: String?, empty: Boolean, emptyText: Int, padding: PaddingValues = PaddingValues(0.dp), retry: () -> Unit) {
    when {
        failure != null -> Column(Modifier.padding(padding).testTag("listFailed").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(failure, color = Color(0xFFFF6B6B))
            OutlinedButton(retry, Modifier.testTag("tryAgain")) { Text(stringResource(R.string.list_tryAgain)) }
        }
        empty -> Text(stringResource(emptyText), color = Text2, modifier = Modifier.padding(padding).testTag("listEmpty"))
    }
}

/**
 * What a list says about a failed load, if anything: nothing when the
 * phone is offline and shows what it kept (the offline banner says so).
 */
fun Model.listFailure(e: Throwable, showing: Boolean): String? =
    if (showing && (offline || e is java.io.IOException)) null else say(e)

/** Runs a list's load; then what the list says about it (null: nothing to say). */
suspend fun Model.loadList(showing: () -> Boolean, load: suspend () -> Unit): String? =
    try {
        load()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        listFailure(e, showing())
    }
