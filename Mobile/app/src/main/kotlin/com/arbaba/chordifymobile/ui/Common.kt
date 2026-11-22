package com.arbaba.chordifymobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The app's background: fills the window with the theme's background colour and gives every text
 *  and icon inside that sets no colour of its own the matching content colour (light in the dark
 *  theme). The Scaffold of the old bottom bar used to do this. */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
}

/** A section heading: the text upper-cased, labelMedium, LocalChordifyColors.current.muted. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, color = LocalChordifyColors.current.muted)
}

/** A plain top bar in the surface colour: a 48 dp row, then a 1 dp outlineVariant divider (49 dp
 *  in all). The row holds an optional Back icon button (Icons.AutoMirrored.Filled.ArrowBack,
 *  contentDescription "Back", testTag "back"), the title (titleMedium, bold, one line,
 *  ellipsised), a flexible gap, then [actions]. testTag "topBar". */
@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier,
                 actions: @Composable RowScope.() -> Unit = {}) {
    Surface(modifier.fillMaxWidth().testTag("topBar"), color = MaterialTheme.colorScheme.surface) { Column {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    } }
}

/** Gives [content] whether the space it fills is wider than tall (BoxWithConstraints:
 *  maxWidth > maxHeight). The landscape/portrait switch of every M7 screen. */
@Composable
fun AdaptiveLayout(modifier: Modifier = Modifier, content: @Composable (landscape: Boolean) -> Unit) {
    BoxWithConstraints(modifier) { content(maxWidth > maxHeight) }
}

/** Two panes: side by side when [landscape] (first takes [firstFraction] of the width, a 1 dp
 *  outlineVariant divider between, each pane fills the height and scrolls vertically on its own),
 *  else stacked in one vertically scrolling column. Each pane gets 16 dp padding. */
@Composable
fun TwoPanes(landscape: Boolean, modifier: Modifier = Modifier, firstFraction: Float = 0.5f,
             first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    if (landscape) {
        Row(modifier.fillMaxSize()) {
            Column(Modifier.weight(firstFraction).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), content = first)
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(1f - firstFraction).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp), content = second)
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(16.dp), content = first)
            Column(Modifier.fillMaxWidth().padding(16.dp), content = second)
        }
    }
}
