# Project agent instructions

## UI verification gate

Every change that affects Android UI must be verified on an Android emulator before completion.

1. Build the debug APK and install it on an emulator. Always pass an explicit emulator serial to `adb`; never run UI verification against a connected physical device.
2. Exercise the affected flow with automated input, including permission dialogs, navigation, primary actions, and relevant loading, empty, populated, and overlay states.
3. Capture emulator screenshots and visually inspect layout, clipping, spacing, typography, colors, system insets, long text, and interaction state.
4. Check the application log for crashes. A successful Gradle build alone is not sufficient UI verification.
5. If the result is wrong, adjust the implementation and repeat. Stop after at most five visual iterations for one requested UI change. If iteration five still fails, stop and report the remaining defects instead of continuing.
6. Store final verification screenshots under `output/ui-tests/` when practical.
