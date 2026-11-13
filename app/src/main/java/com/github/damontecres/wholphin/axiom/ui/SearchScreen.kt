package com.github.damontecres.wholphin.axiom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.damontecres.wholphin.axiom.data.ChannelEntity
import com.github.damontecres.wholphin.axiom.data.MovieEntity
import com.github.damontecres.wholphin.axiom.data.SeriesEntity
import com.github.damontecres.wholphin.axiom.library.LibraryViewModel

/** One search box across channels, films and series. */
@Composable
fun SearchScreen(
    library: LibraryViewModel,
    onPlayChannel: (ChannelEntity) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
) {
    val query by library.searchQuery.collectAsState()
    val results by library.searchResults.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 40.dp),
    ) {
        item(key = "box") {
            Column(modifier = Modifier.padding(horizontal = 40.dp)) {
                Text("Search", color = AxiomColors.Text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                OutlinedTextField(
                    value = query,
                    onValueChange = { library.searchQuery.value = it },
                    placeholder = { Text("Channels, films and series") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AxiomColors.Gold,
                            cursorColor = AxiomColors.Gold,
                            unfocusedBorderColor = AxiomColors.PanelHigh,
                        ),
                    modifier =
                        Modifier
                            .padding(top = 12.dp)
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .focusRequester(focus),
                )
            }
        }

        if (results.query.isNotEmpty() && results.isEmpty) {
            item(key = "none") {
                Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Nothing found for \"${results.query}\"", color = AxiomColors.TextMuted, fontSize = 16.sp)
                }
            }
        }

        if (results.channels.isNotEmpty()) {
            item(key = "channels") {
                HomeRow("Channels") {
                    items(results.channels, key = { it.streamId }) { channel ->
                        ChannelTile(channel) { onPlayChannel(channel) }
                    }
                }
            }
        }
        if (results.movies.isNotEmpty()) {
            item(key = "movies") {
                HomeRow("Films") {
                    items(results.movies, key = { it.streamId }) { movie ->
                        PosterTile(title = movie.name, subtitle = movie.year?.toString(), image = movie.poster, badge = movie.quality) {
                            onOpenMovie(movie)
                        }
                    }
                }
            }
        }
        if (results.series.isNotEmpty()) {
            item(key = "series") {
                HomeRow("Series") {
                    items(results.series, key = { it.seriesId }) { series ->
                        PosterTile(title = series.name, subtitle = series.year?.toString(), image = series.poster) {
                            onOpenSeries(series)
                        }
                    }
                }
            }
        }
    }
}
