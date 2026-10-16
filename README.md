# Virtual Mouse

Android application providing a virtual floating mouse cursor, on-screen trackpad with neon styling, and a full-featured system on-screen keyboard (IME) with modifier keys and clipboard history.

## Features

- **Floating Trackpad & Mouse Cursor**:
  - Movable, resizable, and lockable floating trackpad (🔒).
  - Left click (with hold-to-drag), right click, and scroll wheel emulation.
  - Smooth cursor overlay with customizable styles (arrow, cross, dot, hand), neon glow, and sensitivity controls.
  - Floating trigger button (🖱) to toggle trackpad visibility or access quick settings via long press.
- **Full On-Screen Keyboard IME (`VirtualMouseIME`)**:
  - Multilingual layouts: English (EN), Russian (RU), Ukrainian (UK).
  - Function keys (F1–F12), Esc, Tab, sticky modifier keys (Ctrl, Alt, Shift, Win), Caps Lock, and navigation cluster (Arrow keys, Home, End, PgUp, PgDn, Delete).
  - Layer switching for numbers (`?123`), symbols (`#+=`), emojis, and Unicode symbols (math, arrows, BiDi).
  - Integrated in-memory clipboard history (📋) with quick insertion.
- **Accessibility Integration (`MouseAccessibilityService`)**:
  - Dispatches gesture clicks, scrolls, drags, and navigation triggers (Back, Home, Recents) directly through Android's Accessibility framework.

## Project Structure

```
.
├── .github/workflows/android-build.yml
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/virtualmouse/
│       │   ├── MainActivity.kt
│       │   ├── OverlayService.kt
│       │   ├── MouseAccessibilityService.kt
│       │   ├── VirtualMouseIME.kt
│       │   ├── TouchpadWindow.kt
│       │   ├── CursorOverlay.kt
│       │   ├── FloatingButton.kt
│       │   ├── SettingsWindow.kt
│       │   ├── KeyboardLayouts.kt
│       │   ├── CursorSettings.kt
│       │   ├── PreferenceHelper.kt
│       │   └── ClipboardManager.kt
│       └── res/
│           ├── drawable/
│           ├── layout/
│           ├── mipmap-*/
│           ├── values/
│           └── xml/
├── build.gradle.kts
├── settings.gradle.kts
└── metadata.json
```

## Setup & Permissions

1. **Draw Over Other Apps (`SYSTEM_ALERT_WINDOW`)**: Required for the floating cursor and touchpad overlay.
2. **Accessibility Service (`MouseAccessibilityService`)**: Required for dispatching cursor clicks and gestures.
3. **Input Method (`VirtualMouseIME`)**: Enable in Android Settings → Language & Input → On-Screen Keyboard.
4. **Notifications (`POST_NOTIFICATIONS`)**: Keeps the foreground service running smoothly with persistent control notifications.
