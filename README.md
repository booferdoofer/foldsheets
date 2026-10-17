# FoldSheet — editable spreadsheet widget for Galaxy Z Fold

A home-screen widget that shows a spreadsheet grid. Tap any cell to edit it;
every keystroke saves instantly and the widget updates live.

## What's in it
- **Widget** (Jetpack Glance): grid with column letters and row numbers, scrolls
  vertically through 50 rows. Column count adapts to the widget's actual width, so
  it shows ~3 columns on the cover screen and more on the inner screen.
- **Tap a cell** → small pop-up editor over the home screen. `Next ↓` (or the
  keyboard's Next key) moves down a row; `Clear` empties the cell.
- **Open ↗ / tap the title** → full-screen editor app with all 12 columns (A–L),
  type directly into cells, scrolls both ways.
- Colors follow your One UI wallpaper theme (Material You), light and dark.
- Data is stored on the phone (SharedPreferences), nothing leaves the device.

## Build & install
1. Install **Android Studio** (Ladybug 2024.2 or newer) on your computer.
2. File → Open → select the `FoldSheet` folder. Let Gradle sync finish
   (it downloads Gradle 8.11.1 and the Android libraries automatically; if it
   asks to create the Gradle wrapper, accept).
3. On the Fold: Settings → About phone → Software information → tap
   **Build number** 7 times → back to Settings → Developer options →
   turn on **USB debugging** (or **Wireless debugging**).
4. Plug the phone in, pick it in the device dropdown, press **Run ▶**.
5. Long-press the home screen → **Widgets** → **FoldSheet** → drag it out and
   resize. Add one on the cover screen too if you like — both show the same data.

## Tweaks
- Grid size: `ROWS` / `COLS` in `SheetStore.kt`.
- Cell sizes on the widget: `MIN_COL_W`, `CELL_H` in `SheetWidget.kt`.
- Cell sizes in the app: `COL_W`, `ROW_H` in `MainActivity.kt`.

## Note
Android widgets can't host a text box directly on the home screen, which is why
editing opens the floating pop-up rather than typing in the widget itself.
