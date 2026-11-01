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
| `frames/s`, `p50`, `p95`, `slow` | Real frame times from Android's FrameMetrics. `slow` = frames longer than one refresh interval. |
| `engine` | Time the engine spent per frame (bindings + layout + animations + recording). |
| `per s: bindings · redraws · moves` | Binding re-evaluations, re-recorded drawings, and transform-only updates per second. |

What you should see: while idle, nothing redraws except once a minute (or once a second on
the page that shows seconds). While scrolling, `redraws` stays at 0 and only `moves` goes up.

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
