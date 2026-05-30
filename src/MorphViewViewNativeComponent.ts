import type { ViewProps, ColorValue, CodegenTypes } from 'react-native';
import { codegenNativeComponent } from 'react-native';

type Float = CodegenTypes.Float;

// Fabric codegen spec. Props are kept to primitive types (string/float/bool/color)
// so codegen maps them cleanly on both platforms. The ergonomic public API lives in
// `MorphView.tsx`, which resolves RN image sources down to the `fromUri` / `toUri` strings
// and always supplies blurRadius / durationMs, so native defaults are not relied upon.
export interface NativeProps extends ViewProps {
  /** Resolved URI of the "from" image (shown when toggle is false). */
  fromUri?: string;
  /** Resolved URI of the "to" image (shown when toggle is true). */
  toUri?: string;
  /** Which image is shown. Changing this animates the gooey morph. */
  toggle?: boolean;
  /** Peak blur radius (dp/pt) reached at the midpoint of the morph. */
  blurRadius?: Float;
  /** Morph duration in milliseconds. */
  durationMs?: Float;
  /** Optional template tint applied to both images (SF-symbol-like recolor). */
  tintColor?: ColorValue;
}

export default codegenNativeComponent<NativeProps>('MorphViewView');
