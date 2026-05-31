import { Image, type ColorValue, type ViewProps } from 'react-native';
import MorphViewViewNativeComponent from './MorphViewViewNativeComponent';

/**
 * An image source for the morph: either a bundled asset (`require('./x.png')`)
 * or a remote/file image (`{ uri: 'https://…' }`).
 */
export type MorphImageSource = number | { uri: string };

export interface MorphViewProps extends ViewProps {
  /**
   * Which image is shown. Flip it (typically inside an animation trigger / state
   * update) to play the gooey morph from `from` to `to` and back.
   */
  toggle: boolean;
  /** Image shown while `toggle` is false. */
  from: MorphImageSource;
  /** Image shown while `toggle` is true. */
  to: MorphImageSource;
  /**
   * Peak blur applied at the midpoint of the morph (points/dp). Larger = goopier.
   * @default 24
   */
  blurRadius?: number;
  /**
   * Morph duration in milliseconds.
   * @default 600
   */
  duration?: number;
  /**
   * Optional template tint applied to both images — recolors opaque pixels,
   * mimicking the SF-Symbol look from the original effect. Omit to keep the
   * images' own colors.
   */
  tintColor?: ColorValue;
  /**
   * Color of the gooey outline that hugs the morphing silhouette. A border is
   * drawn only when both `borderColor` is set and `borderWidth` is greater than
   * 0 — omit `borderColor` (or set `borderWidth` to 0) to disable it.
   */
  borderColor?: ColorValue;
  /**
   * Thickness of the gooey outline in points. Defaults to 0 (no border).
   * @default 0
   */
  borderWidth?: number;
}

function resolveUri(source: MorphImageSource): string | undefined {
  // Image.resolveAssetSource handles both `require(...)` numbers and `{ uri }`.
  const resolved = Image.resolveAssetSource(
    source as Parameters<typeof Image.resolveAssetSource>[0]
  );
  return resolved?.uri;
}

/**
 * Gooey "metaball" morph between two images. Renders the images natively and
 * blends them with a blur + alpha-threshold shader (Metal on iOS, AGSL on
 * Android) so the shapes melt into one another mid-transition.
 *
 * @example
 * ```tsx
 * const [on, setOn] = useState(false);
 * <MorphView
 *   toggle={on}
 *   from={require('./heart.png')}
 *   to={require('./star.png')}
 *   blurRadius={24}
 *   tintColor="#FF3B6B"
 *   style={{ width: 200, height: 200 }}
 *   onTouchEnd={() => setOn((v) => !v)}
 * />
 * ```
 */
export function MorphView({
  toggle,
  from,
  to,
  blurRadius = 24,
  duration = 600,
  tintColor,
  borderColor,
  borderWidth = 0,
  ...rest
}: MorphViewProps) {
  return (
    <MorphViewViewNativeComponent
      toggle={toggle}
      fromUri={resolveUri(from)}
      toUri={resolveUri(to)}
      blurRadius={blurRadius}
      durationMs={duration}
      tintColor={tintColor}
      morphBorderColor={borderColor}
      morphBorderWidth={borderWidth}
      {...rest}
    />
  );
}
