# Make-A-Launcher

An Android launcher where the whole home screen is a design you control, like KLWP but for
the entire launcher. Presets are plain text files you can edit on the phone, in a web editor,
or in any text editor, and the engine is built so that big presets stay fast.

> **Status: Phase 1, engine prototype.** It runs as a normal app (not yet a launcher) and
> exists to prove the performance design before the launcher is built on top of it.

## Try it

- **Download:** open the latest run of the [Build workflow](../../actions/workflows/build.yml),
  then download `make-a-launcher-debug-apk` under *Artifacts*. Unzip it and install the APK
  (Android 12 or newer).
- **Build it yourself:** `./gradlew :app:assembleDebug` with JDK 17+ and the Android SDK,
  or open the project in Android Studio.

Swipe between the three pages. **Long-press** anywhere to switch to the 300-component stress
test or to hide the stats overlay.

## What Phase 1 proves

The gate for Phase 1 is: **a 300-component preset scrolls at the display's full refresh rate**.
The overlay shows how close we are:

| Line | Meaning |
| --- | --- |
| `frames/s`, `p50`, `p95`, `late` | Real frame times from Android's FrameMetrics. `late` = frames that missed Android's own deadline, which is what shows as stutter. A frame can take longer than one refresh without being late, because the main thread, render thread and GPU work on different frames at once. |
| `engine`, `GPU` | Time the engine spent per updated frame (bindings + layout + animations + recording), and GPU time per frame. |
| `per s: bindings · redraws · moves` | Binding re-evaluations, re-recorded drawings, and transform-only updates per second. |

What you should see: while idle, nothing redraws except once a minute (or once a second on
the page that shows seconds). While scrolling, `redraws` stays at 0 and only `moves` goes up.

## Measuring battery

Battery use comes from three places: work while the screen is on (frames, CPU), work while
the launcher is hidden (wakeups), and radios such as location. The app tracks the first two
and is built to keep them near zero.

| Where | What it shows |
| --- | --- |
| Overlay `power:` line | The app's CPU use (percent of one core), split into the UI thread (the engine) and the render thread (Android turning drawings into GPU commands), and the whole phone's current draw. |
| Overlay `last hidden:` line | Whether anything ran while the app was off screen. It should say *no background work*. |
| Menu → **Battery test: idle** (10 min) | Screen kept on, untouched. Reports CPU % (with the thread split), frames/s, late frames, GPU time, wakeups/min, average phone current and mAh used. |
| Menu → **Battery test: scrolling** (2 min) | Pages scroll by themselves the whole time: the worst case for animation work. |
| Menu → **Battery log** | Totals for time on screen and time hidden, from a log kept across app restarts. **Share** sends it as text with the raw CSV. |
| Menu → **Engine settings** | Turns each optimization below on or off, so a test can show what it saves. |

Phone current includes the screen, which usually dominates, so compare like with like:

1. Set a fixed brightness and turn off adaptive brightness.
2. Run the idle test with **Blank preset (battery baseline)**, then with the preset you're
   checking. The difference is what the preset costs.
3. Run the scrolling test the same way for the cost of animation.
4. To see what an optimization is worth, run the same test with it on and off in
   **Engine settings**. Each result lists which settings were on.

Some phones don't report current or report it coarsely; CPU % and wakeups are reliable on
every phone. To compare with another launcher or KLWP over a whole day, use Android's own
accounting: `adb shell dumpsys batterystats --reset`, use the phone normally, then
`adb shell dumpsys batterystats <package>` and compare the CPU time and estimated power use.

## How it works

```
Data sources ──▶ Reactive graph ──▶ Scene tree ──▶ Render tree ──▶ GPU
(time, battery)  re-runs only        layout,        one RenderNode   Android's
                 what changed        values         per component    render thread
                                                         ▲
Scroll / touch ──▶ Native animator ──────────────────────┘
                   keyframes, transforms only
```

- **Reactive graph.** Every binding records which data it read. A battery change re-runs only
  bindings that show the battery; an `HH:mm` clock wakes up once a minute, never per second.
- **Retained render tree.** Each component records its drawing once into its own `RenderNode`.
  A changed text re-records only itself.
- **Declarative animation.** Scroll animations are keyframes evaluated natively; scrolling never
  runs bindings or re-records drawings, it only moves cached layers.
- **GPU layer caching.** Moving layers that are expensive to draw (groups, large or long text)
  are rendered once into a GPU texture; scrolling then only moves, fades and scales that texture.
  Single-paint layers fade without an offscreen buffer. Layer memory is capped at two screens.
- **Off-screen pages pause.** Bindings on a page that's fully off screen stop listening, and
  catch up in the frame the page comes back into view. A seconds display on another page doesn't
  keep a timer running.
- **Sleep hard.** When the app isn't visible, every timer and listener stops. A seconds timer
  runs only while something on screen shows seconds.

## Repository layout

| Path | What's there |
| --- | --- |
| `core/` | The engine, in pure Kotlin with no Android imports: YAML parser, scene model and loader, reactive graph, templates, layout, scroll animation. Fully unit-tested. |
| `app/` | The Android host: `RenderNode` renderer, page scrolling, time and battery sources, stats overlay. |
| `app/src/main/assets/presets/demo/` | The demo preset. |
| `docs/preset-format.md` | Reference for writing presets by hand. |

## Writing presets

A preset is a folder of YAML files. A minimal layer looks like this:

```yaml
# scene.yaml
pages: 3
layers:
  - id: clock
    type: text
    text: '{time.format("HH:mm")}'
    size: 72
    color: '{globals.accent}'
    anchor: top-center
    offset: [0, 120]
    animate:
      - on: scroll
        translate_y: [0, -60]
        alpha: [1, 0]
```

See [docs/preset-format.md](docs/preset-format.md) for every property.

## Roadmap

1. **Engine prototype** (now): scene model, reactive graph, `RenderNode` renderer, text,
   shapes, images, time and battery. Gate: 300 components scroll at full rate.
2. **Launcher + Luau**: home-screen role, app list, designable app drawer, gestures, Luau
   scripting. Gate: usable as a daily launcher.
3. **Editors**: on-device editor, performance inspector, globals panel, JSON Schema for VS Code,
   CLI with live push to the phone. Gate: closed beta.
4. **Public v1**: web editor, Android widgets, weather and calendar, sharing, icon packs,
   tablets and foldables.

Decisions so far: YAML presets, GPLv3, no monetization, Android 12 minimum, no KLWP importer.

## License

[GPLv3](LICENSE).
