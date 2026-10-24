# Changelog

## 0.4.0

Adds an experimental Metal 4 mode, a Pure Metal line on the F3 screen, and safer startup. Nothing changes unless you turn Metal 4 on.

**New**
- Metal 4 (Experimental): a new option in Video Settings and Sodium's settings that runs Pure Metal on Apple's newer Metal 4 API. It needs macOS 26 and is off by default. Metal 3 is still the faster and more stable choice, so only turn it on if you want to try it. Changing it takes effect after a restart.
- On older macOS versions the Metal 4 option is greyed out, and its tooltip explains why.
- The F3 screen now shows a Pure Metal line: which renderer is running (Metal 3 or Metal 4), your GPU, and the upscaler and render scale in use. Handy for bug reports. You can hide it in the F3 settings like any other line.

**Fixes**
- If Minecraft switched your Graphics API to OpenGL after a crash that had nothing to do with Metal, Pure Metal now switches it back to Default.
- More reliable startup crash recovery: Pure Metal only skips Metal after a start that really failed, and running two game instances at once no longer confuses this check.
- The log now says why Metal didn't start, for example when Graphics API isn't set to Default.
- The note under Pure Metal's title in Sodium's settings is easier to read on bright backgrounds.

**Compatibility**
- Tested with and without Sodium 0.9.2.
- Tested on an Apple M2. Other Macs should work but were not tested for this release.
- Iris and other shader mods are still not supported.

Everything from 0.3.2 is included.
