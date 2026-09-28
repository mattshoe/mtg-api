package org.mattshoe.mtg.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Completion

/**
 * A text field that suggests card names. Sibling of `AutocompleteField`
 * on the web.
 *
 * What is highlighted and when it is worth asking is `Completion` in
 * the shared core; the caller does the lookup, so being offline is a
 * quiet empty list on both platforms rather than two different errors.
 */
@Composable
fun AutocompleteField(
    label: String,
    state: Completion,
    onState: (Completion) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.term,
            onValueChange = { onState(state.typed(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(label) },
        )
        if (state.open && !state.isEmpty) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                state.items.forEachIndexed { i, name ->
                    Text(
                        name,
                        Modifier.fillMaxWidth()
                            .clickable {
                                val (next, picked) = state.pick(i)
                                onState(next)
                                picked?.let(onPick)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        fontSize = 14.sp,
                        fontWeight = if (i == state.active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
