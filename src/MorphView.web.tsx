import { useEffect, useRef } from 'react';
import { Animated, Easing, Image, StyleSheet } from 'react-native';
import type { MorphViewProps, MorphImageSource } from './MorphView';

export type { MorphImageSource, MorphViewProps } from './MorphView';

function resolveUri(source: MorphImageSource): string | undefined {
  const resolved = Image.resolveAssetSource(
    source as Parameters<typeof Image.resolveAssetSource>[0]
  );
  return resolved?.uri;
}

/**
 * Web fallback. The browser can't run the Metal/AGSL alpha-threshold shader, so this approximates
 * the effect with an ease-in-out crossfade plus a static soft blur on each image. No metaball
 * fusing — for that, run on iOS or Android.
 */
export function MorphView({
  toggle,
  from,
  to,
  blurRadius = 24,
  duration = 600,
  tintColor,
  style,
  ...rest
}: MorphViewProps) {
  const progress = useRef(new Animated.Value(toggle ? 1 : 0)).current;

  useEffect(() => {
    Animated.timing(progress, {
      toValue: toggle ? 1 : 0,
      duration,
      easing: Easing.inOut(Easing.ease),
      useNativeDriver: false,
    }).start();
  }, [toggle, duration, progress]);

  const fromOpacity = progress.interpolate({
    inputRange: [0, 1],
    outputRange: [1, 0],
  });
  const softBlur = Math.round(blurRadius / 3);
  const tint = tintColor ? { tintColor } : null;

  return (
    <Animated.View style={style} {...rest}>
      <Animated.Image
        source={{ uri: resolveUri(from) }}
        resizeMode="contain"
        blurRadius={softBlur}
        style={[styles.fill, tint, { opacity: fromOpacity }]}
      />
      <Animated.Image
        source={{ uri: resolveUri(to) }}
        resizeMode="contain"
        blurRadius={softBlur}
        style={[styles.fill, tint, { opacity: progress }]}
      />
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  fill: { position: 'absolute', width: '100%', height: '100%' },
});
