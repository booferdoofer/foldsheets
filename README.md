# Fold Blur (prototype v0.1)

Adds a soft, iPhone-style blur when the Galaxy Fold opens or closes. It doesn't touch Smart Launcher, your wallpapers, icons, widgets or layouts.

## How it works
- **While you fold or unfold**, the hinge sensor reports movement. A blur layer fades in over whatever is on screen in about 90 ms.
- **When the screen switches** between the cover and inner displays, the new screen appears already blurred. It then comes into focus over about 320 ms with a soft ease-out.
- The blur is done by Android's own compositor (`FLAG_BLUR_BEHIND`). There's no screen capture, no Accessibility permission, no Shizuku and no root.
- The blur window can't be touched and is removed completely after each transition. A 2-second safety timeout means a blur can never get stuck on screen.
- One small listener stays running and reacts only to events. It turns off whenever the screen is off. Android requires a silent, minimised notification for it.

## Build the APK (no Android Studio needed)
1. Create a **private** GitHub repo and upload this whole folder. The `.github` folder must be included.
2. Open the repo's **Actions** tab. The "Build APK" run starts on its own and takes about 3–5 minutes.
3. Open the finished run and download **FoldBlur-apk**. Unzip it to get `app-release.apk`.

Every build uses the same signing key (`app/foldblur.keystore`), so new versions install over old ones and keep your settings. Keep the repo private, because the key is stored in it.

## Install and set up
1. Move the APK to your phone, open it and allow "Install unknown apps" for your file manager.
2. Open **Fold Blur**:
   - Tap **Allow "Display over other apps"** and turn it on.
   - Tap **Battery → Unrestricted**. Otherwise One UI may shut the listener down.
   - Turn on **Enabled**.
3. Tap **Preview transition** to tune the look before folding.

## What this prototype is testing
The checklist at the top of the app shows four things. All four should be ✓:
- **System blur available.** This turns off in Power saving mode. When it's off, you only get the dim fade.
- **Hinge sensor.** The live readout should change as you fold.
- **Panel.** The resolution should change when you switch between cover and inner screens. That change is how the app detects the handoff.

## Known limits (by design, for now)
- **No zoom.** A normal app can blur and dim what's behind it, but it can't scale it. Zoom would require capturing the screen, and we chose not to do that.
- **Status bar and navigation bar stay sharp.** Android draws them above every third-party window.
- **Flex mode.** Small hinge adjustments of 3° or more cause a brief blur. You can switch off "Start blurring while the hinge moves" to blur only when the screen actually switches. The trade-off is that the new screen may flash sharp for a frame first.
