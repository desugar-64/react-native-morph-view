# react-native-morph-view

https://github.com/user-attachments/assets/4d66f6e4-369d-44ca-bf58-e3152bc87e5c

A React Native view that smoothly morphs one image into another. Flip a single `toggle` prop and the two shapes blend together with a native shader for a fluid, gooey transition.

- **Real gooey morph on both platforms** — a blur + alpha-threshold shader (Metal on iOS, AGSL on Android) fuses the two silhouettes instead of crossfading them
- **One prop drives everything** — flip `toggle` and the view animates from `from` to `to` and back; no imperative animation code
- **Works with any image** — bundled assets (`require(...)`), remote URLs, or file URIs, via the familiar React Native image source shape
- **Gooey outline** — an optional border traces the morphing silhouette, not a rectangular CSS box around the view
- **Tunable goop** — `blurRadius` controls how molten the midpoint looks, `duration` controls the timing
- **One API, three platforms** — iOS, Android, and Web share the same `<MorphView />`; web gets an animated crossfade fallback
- **Fabric / New Architecture only**

## Installation

```sh
npm install react-native-morph-view
```

### Requirements

- React Native 0.76+ (New Architecture / Fabric)
- iOS 15.1+
- Android API 33+ (AGSL runtime shaders require Android 13+; older devices fall back to a crossfade)

### Web setup

On web, `MorphView` resolves image sources through `Image.resolveAssetSource`, which React Native Web doesn't provide. If you target web, polyfill it before rendering: https://github.com/necolas/react-native-web/pull/2814

## Usage

Render a `MorphView`, give it two images, and flip `toggle` to play the morph:

```tsx
import { useState } from 'react';
import { MorphView } from 'react-native-morph-view';

export default function App() {
  const [on, setOn] = useState(false);

  return (
    <MorphView
      toggle={on}
      from={require('./assets/heart.png')}
      to={require('./assets/star.png')}
      blurRadius={24}
      tintColor="#FF3B6B"
      style={{ width: 200, height: 200 }}
      onTouchEnd={() => setOn((v) => !v)}
    />
  );
}
```

`MorphView` extends `ViewProps`, so layout, `style`, touch handlers, and accessibility props all work as usual. Use a remote or file image anywhere you'd use a bundled one:

```tsx
<MorphView
  toggle={on}
  from={require('./assets/coffee.png')}
  to={{ uri: 'https://example.com/battery.png' }}
  style={{ width: 160, height: 160 }}
/>
```

## API

### `<MorphView>`

Renders both images and animates the gooey morph between them whenever `toggle` changes.

| Prop           | Type               | Default | Description                                                                                                                                             |
| -------------- | ------------------ | ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `toggle`       | `boolean`          | —       | Which image is shown. `false` → `from`, `true` → `to`. Flipping it plays the morph in that direction. **Required.**                                    |
| `from`         | `MorphImageSource` | —       | Image shown while `toggle` is `false`. `require(...)`, `{ uri }`, or a file URI. **Required.**                                                         |
| `to`           | `MorphImageSource` | —       | Image shown while `toggle` is `true`. **Required.**                                                                                                    |
| `blurRadius`   | `number`           | `24`    | Peak blur applied at the midpoint of the morph (points/dp). Larger = goopier.                                                                          |
| `duration`     | `number`           | `600`   | Morph duration in milliseconds.                                                                                                                        |
| `tintColor`    | `ColorValue`       | —       | Optional template tint applied to both images — recolors opaque pixels, mimicking the SF-Symbol look. Omit to keep each image's own colors.            |
| `borderColor`  | `ColorValue`       | —       | Color of the gooey outline that hugs the morphing silhouette. Drawn only when `borderColor` is set **and** `borderWidth` is greater than 0.            |
| `borderWidth`  | `number`           | `0`     | Thickness of the gooey outline in points. `0` disables it.                                                                                             |
| `...ViewProps` | `ViewProps`        | —       | All standard `View` props — `style`, `onTouchEnd`, `accessibilityLabel`, etc.                                                                          |

### `MorphImageSource`

```ts
type MorphImageSource = number | { uri: string };
```

A bundled asset (`require('./x.png')` resolves to a `number`) or a remote / file image (`{ uri: 'https://…' }`).

## How it works

Both images are rendered natively, then blended through an alpha-threshold shader. As `toggle` flips, each image's blur ramps up toward `blurRadius` at the midpoint and back down, while the threshold step re-sharpens the combined alpha — so overlapping blurred regions fuse into a single connected blob (the "metaball" effect) before resolving into the target shape.

- **iOS** runs the effect as a Metal shader.
- **Android** runs it as an AGSL `RuntimeShader` (Android 13+).
- **Web** can't run the native shader, so it approximates the morph with an ease-in-out crossfade plus a static soft blur on each image. No metaball fusing — for that, run on iOS or Android.

## Tips

- For the cleanest goo, use images with transparent backgrounds and solid, opaque shapes — the shader works on the alpha channel.
- Pair `MorphView` with `LayoutAnimation` if the surrounding layout also changes when you toggle, so the whole transition stays in sync.
- Increase `blurRadius` for blobbier, more liquid transitions; decrease it for a tighter, snappier morph.
- Use `tintColor` to drive monochrome glyphs from your theme without shipping pre-colored assets.

## Examples

A runnable demo lives in [`example/`](./example) — an Expo app that morphs between shapes, photos, and a multi-image gallery, with live controls for `blurRadius`, `duration`, `tintColor`, and the gooey border.

```sh
yarn install
yarn example ios      # or: yarn example android / yarn example web
```

```tsx
// Morph through any-length list of images
const GALLERY = [
  require('./assets/blob.png'),
  require('./assets/star.png'),
  require('./assets/expo.png'),
];

// Gooey outline + template tint
<MorphView
  toggle={on}
  from={GALLERY[0]}
  to={GALLERY[1]}
  tintColor="#0A84FF"
  borderColor="#0A84FF"
  borderWidth={3}
  blurRadius={32}
  style={{ width: 200, height: 200 }}
/>;
```

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT
