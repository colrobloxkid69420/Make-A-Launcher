# Preset format (schema 1)

A preset is a folder:

```
my-preset/
  preset.yaml    name, author, version, schema
  globals.yaml   theme settings that layers read with {globals.name}
  scene.yaml     pages and layers (required)
  fonts/, images/  any assets the layers reference
```

Only `scene.yaml` is required. When something is wrong, the app lists every problem with its
file and line number instead of stopping at the first one.

## YAML rules

Presets use a strict subset of YAML so the phone editor can always rewrite what it loaded:

- Indent with spaces, never tabs.
- Lists: `- item`. Mappings: `key: value`. One-line lists `[a, b]` and mappings `{a: 1}` work.
- Quote values that start with `{`, `[`, `#`, `@` or contain `: `:
  `color: '{globals.accent}'`, `fill: '#FF202830'`.
- Multi-line text uses `|` (keep line breaks) or `>` (fold into one line); add `-` to drop the
  final line break: `text: |-`.
- Not supported: anchors (`&`), aliases (`*`), tags (`!`), several documents in one file.

## preset.yaml

```yaml
name: My preset
author: Me
version: 1.0.0
schema: 1
capabilities: [battery]
```

## globals.yaml

Each entry becomes `{globals.<name>}`. The type is inferred from the value, or set explicitly:

```yaml
accent: '#FF7AA2F7'          # colour
corner: 24                   # number
greeting: Good morning       # text
show_seconds: false          # switch
card: {type: color, value: '#FF1F2335'}
```

## scene.yaml

```yaml
pages: 3                  # home-screen pages, 1 to 20
background: '#FF16161E'   # optional colour behind everything
layers:                   # drawn in order, first at the back
  - ...
```

## Layers

Every layer has an `id` (keep it unique and stable; the editor uses it) and a `type`.

### Properties every layer has

| Property | Value | Default |
| --- | --- | --- |
| `id` | unique text | generated, with a warning |
| `type` | `text`, `shape`, `image`, `group` | required |
| `page` | page number, top-level layers only; the layer then moves with page scrolling | fixed on screen |
| `anchor` | `top-left`, `top-center`, `top-right`, `center-left`, `center`, `center-right`, `bottom-left`, `bottom-center`, `bottom-right` | `top-left` |
| `offset` | `[x, y]` in dp from the anchor point | `[0, 0]` |
| `width`, `height` | dp | depends on type |
| `alpha` | 0 to 1 | 1 |
| `rotation` | degrees | 0 |
| `scale` | multiplier | 1 |
| `visible` | `true`, `false`, or a template | `true` |
| `animate` | list of animations (below) | none |

`anchor` lines up the same point on the layer and its parent: `center` puts the layer's centre
on the parent's centre, `bottom-right` its bottom-right corner on the parent's.

### text

| Property | Value | Default |
| --- | --- | --- |
| `text` | text, may contain `{...}` | empty |
| `size` | dp | 16 |
| `color` | colour | white |
| `bold` | `true` / `false` | `false` |
| `font` | path to a font file in the preset | system font |
| `align` | `start`, `center`, `end` (follows the text direction, so it works for right-to-left languages) | `start` |
| `width` | wrap width in dp; without it the text is one line per paragraph | natural width |
| `max_lines` | lines before the text is cut with "…" | unlimited |

### shape

| Property | Value | Default |
| --- | --- | --- |
| `shape` | `rect` or `oval` | `rect` |
| `width`, `height` | dp | 100 × 100 |
| `radius` | corner radius in dp | 0 |
| `fill` | colour | none |
| `stroke` | colour | none |
| `stroke_width` | dp | 1 |

### image

| Property | Value | Default |
| --- | --- | --- |
| `src` | path to an image in the preset | required |
| `fit` | `cover`, `contain`, `fill` | `cover` |
| `tint` | colour | none |
| `width`, `height` | dp | the image's own size |

### group

A group holds `children` (a list of layers) positioned inside the group's box. Without a
`width`/`height` a group fills its parent.

## Values

- **Sizes and offsets** are in dp, so a preset looks the same on any screen density.
- **Colours** are `#RRGGBB` or `#AARRGGBB` (alpha first, as in Android and KLWP).
- **Templates**: any value can contain `{...}` expressions. Write `{{` and `}}` for literal braces.

### Data available in templates (Phase 1)

| Expression | Value | Updates |
| --- | --- | --- |
| `time.format("HH:mm")` | the time formatted with a [Java date pattern](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html) | once a minute, or once a second if the pattern has seconds |
| `time.hour`, `time.minute` | numbers | once a minute |
| `time.second` | number | once a second |
| `battery.level` | 0 to 100 | when it changes |
| `battery.charging` | `true` / `false` | when it changes |
| `globals.<name>` | a value from globals.yaml | when the setting changes |

Phase 2 replaces this small expression language with Luau scripting; templates keep working.

## Animations

Animations are bound to the home-screen scroll position, measured in pages (0 = first page).
They run natively every frame without re-evaluating anything, so they're cheap.

```yaml
animate:
  - on: scroll
    range: [0, 1]            # scroll positions the animation spans; default: first to last page
    ease: out                # linear, in, out, in-out
    translate_x: [0, -200]   # dp
    translate_y: [0, 40]     # dp
    alpha: [1, 0]
    scale: [1, 0.9]
    rotation: [0, 15]        # degrees
```

Each list's values are spread evenly across the range: `alpha: [1, 0.3, 1]` over three pages
is fully visible on pages 1 and 3 and dim on page 2.
