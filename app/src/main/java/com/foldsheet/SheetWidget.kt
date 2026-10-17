package com.foldsheet

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle

class SheetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SheetWidget()
}

class SheetWidget : GlanceAppWidget() {
    // Exact = recompose at the real size, so the grid reflows when you
    // move between the Fold's cover screen and inner screen.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme { SheetContent() }
        }
    }
}

private val ROW_HEADER_W = 30.dp
private val MIN_COL_W = 72.dp
private val CELL_H = 30.dp
private val GAP = 1.dp

@Composable
private fun SheetContent() {
    val context = LocalContext.current
    val cells by SheetStore.flow(context).collectAsState()
    val size = LocalSize.current

    val outerPad = 8.dp
    val avail = size.width - outerPad * 2 - ROW_HEADER_W
    val cols = (avail.value / (MIN_COL_W.value + GAP.value)).toInt().coerceIn(1, SheetStore.COLS)
    val cellW: Dp = ((avail.value - GAP.value * cols) / cols).dp

    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(GlanceTheme.colors.widgetBackground)
            .padding(outerPad)
    ) {
        // Title bar
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FoldSheet",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight().clickable(openApp)
            )
            Text(
                text = "Open ↗",
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp),
                modifier = GlanceModifier.padding(horizontal = 6.dp).clickable(openApp)
            )
        }

        // Column letters
        Row(modifier = GlanceModifier.background(GlanceTheme.colors.surfaceVariant)) {
            HeaderCell("", ROW_HEADER_W)
            for (c in 0 until cols) {
                Spacer(GlanceModifier.width(GAP))
                HeaderCell(SheetStore.colName(c), cellW)
            }
        }

        // Rows (scrollable)
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            items(SheetStore.ROWS, itemId = { it.toLong() }) { r ->
                Row(
                    modifier = GlanceModifier
                        .background(GlanceTheme.colors.surfaceVariant)
                        .padding(top = GAP)
                ) {
                    HeaderCell((r + 1).toString(), ROW_HEADER_W)
                    for (c in 0 until cols) {
                        Spacer(GlanceModifier.width(GAP))
                        val value = cells[r * SheetStore.COLS + c] ?: ""
                        Text(
                            text = value,
                            maxLines = 1,
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
                            modifier = GlanceModifier
                                .width(cellW)
                                .height(CELL_H)
                                .background(GlanceTheme.colors.surface)
                                .padding(horizontal = 5.dp, vertical = 6.dp)
                                .clickable(actionStartActivity(editIntent(context, r, c)))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(label: String, width: Dp) {
    Text(
        text = label,
        maxLines = 1,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        ),
        modifier = GlanceModifier.width(width).height(CELL_H).padding(top = 8.dp)
    )
}

/** Unique data URI per cell so Android keeps each tap target distinct. */
private fun editIntent(context: Context, row: Int, col: Int): Intent =
    Intent(context, EditCellActivity::class.java)
        .setData(Uri.parse("foldsheet://cell/$row/$col"))
