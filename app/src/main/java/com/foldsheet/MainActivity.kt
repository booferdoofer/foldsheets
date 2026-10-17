package com.foldsheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ROW_HEADER_W = 40.dp
private val COL_W = 104.dp
private val ROW_H = 44.dp

/** Full-screen editor. Same data as the widget; edits save as you type. */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Scaffold(
                    topBar = { CenterAlignedTopAppBar(title = { Text("FoldSheet") }) }
                ) { padding ->
                    SheetGrid(Modifier.padding(padding))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SheetStore.refreshWidgets(this)
    }
}

@Composable
private fun SheetGrid(modifier: Modifier = Modifier) {
    val hScroll = rememberScrollState() // shared so header + rows scroll together
    val colors = MaterialTheme.colorScheme

    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(Modifier.background(colors.surfaceVariant)) {
            Header("", ROW_HEADER_W)
            Row(Modifier.horizontalScroll(hScroll)) {
                for (c in 0 until SheetStore.COLS) Header(SheetStore.colName(c), COL_W)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(SheetStore.ROWS, key = { it }) { r -> SheetRow(r, hScroll) }
        }
    }
}

@Composable
private fun SheetRow(row: Int, hScroll: ScrollState) {
    Row {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            Header((row + 1).toString(), ROW_HEADER_W)
        }
        Row(Modifier.horizontalScroll(hScroll)) {
            for (c in 0 until SheetStore.COLS) Cell(row, c)
        }
    }
}

@Composable
private fun Cell(row: Int, col: Int) {
    val context = LocalContext.current
    val cells by SheetStore.flow(context).collectAsState()
    val stored = cells[row * SheetStore.COLS + col] ?: ""
    var text by remember(row, col) { mutableStateOf(stored) }
    // Pick up edits made from the widget while the app is open.
    LaunchedEffect(stored) { if (stored != text) text = stored }

    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            SheetStore.set(context, row, col, it)
        },
        singleLine = true,
        textStyle = TextStyle(color = colors.onSurface, fontSize = 15.sp),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .width(COL_W)
            .height(ROW_H)
            .border(0.5.dp, colors.outlineVariant)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    )
}

@Composable
private fun Header(label: String, width: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.width(width).height(ROW_H).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
