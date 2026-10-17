package com.foldsheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Small pop-up editor opened by tapping a cell on the widget.
 * Every keystroke is saved immediately; "Next ↓" moves to the cell below.
 */
class EditCellActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seg = intent.data?.pathSegments
        val row = seg?.getOrNull(0)?.toIntOrNull()
        val col = seg?.getOrNull(1)?.toIntOrNull()
        if (row == null || col == null) {
            finish()
            return
        }
        setContent {
            AppTheme {
                CellEditor(startRow = row, col = col, onClose = { finish() })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SheetStore.refreshWidgets(this)
    }
}

@Composable
private fun CellEditor(startRow: Int, col: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    var row by remember { mutableIntStateOf(startRow) }
    var field by remember(row) {
        val v = SheetStore.get(context, row, col)
        mutableStateOf(TextFieldValue(v, TextRange(v.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(row) { focus.requestFocus() }

    val canGoDown = row < SheetStore.ROWS - 1
    val goDown: () -> Unit = { if (canGoDown) row++ else onClose() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(SheetStore.cellName(row, col)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    SheetStore.set(context, row, col, it.text)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { goDown() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        dismissButton = {
            TextButton(onClick = {
                field = TextFieldValue("")
                SheetStore.set(context, row, col, "")
            }) { Text("Clear") }
        },
        confirmButton = {
            Row {
                if (canGoDown) TextButton(onClick = { goDown() }) { Text("Next ↓") }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }
    )
}
