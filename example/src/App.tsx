import { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Easing,
  LayoutAnimation,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Switch,
  Text,
  UIManager,
  View,
  useColorScheme,
  type ColorValue,
  type LayoutChangeEvent,
} from 'react-native';
import { MorphView, type MorphImageSource } from 'react-native-morph-view';

if (
  Platform.OS === 'android' &&
  UIManager.setLayoutAnimationEnabledExperimental
) {
  UIManager.setLayoutAnimationEnabledExperimental(true);
}

/* ------------------------------------------------------------------ assets */

const SHAPES = {
  blob: require('../assets/shapes/blob.png'),
  star: require('../assets/shapes/star.png'),
  expo: require('../assets/shapes/expo.png'),
  react: require('../assets/shapes/react.png'),
};

const PHOTOS = {
  from: require('../assets/coffee.png'),
  to: {
    uri: 'https://static.vecteezy.com/system/resources/thumbnails/069/732/319/small/two-aa-batteries-crossed-isolated-on-transparent-background-png.png',
  },
} as const;

/* ------------------------------------------------------------------- theme */

// Apple system colors, light & dark — mirrors SwiftUI's semantic palette so the
// screen feels at home beside a native Settings app.
function useTheme() {
  const dark = useColorScheme() === 'dark';
  return dark
    ? {
        dark,
        groupedBg: '#000000',
        card: '#1C1C1E',
        label: '#FFFFFF',
        secondary: 'rgba(235,235,245,0.6)',
        tertiary: 'rgba(235,235,245,0.3)',
        separator: 'rgba(84,84,88,0.65)',
        fill: 'rgba(118,118,128,0.24)',
        segmentThumb: '#636366',
        accent: '#0A84FF',
        canvasTop: '#2C2C2E',
        canvasBottom: '#202022',
      }
    : {
        dark,
        groupedBg: '#F2F2F7',
        card: '#FFFFFF',
        label: '#000000',
        secondary: 'rgba(60,60,67,0.6)',
        tertiary: 'rgba(60,60,67,0.3)',
        separator: 'rgba(60,60,67,0.29)',
        fill: 'rgba(118,118,128,0.12)',
        segmentThumb: '#FFFFFF',
        accent: '#007AFF',
        canvasTop: '#FFFFFF',
        canvasBottom: '#ECECEF',
      };
}
type Theme = ReturnType<typeof useTheme>;

const TINTS: { name: string; value?: ColorValue }[] = [
  { name: 'Original', value: undefined },
  { name: 'Pink', value: '#FF375F' },
  { name: 'Blue', value: '#0A84FF' },
  { name: 'Green', value: '#30D158' },
  { name: 'Indigo', value: '#5E5CE6' },
];

const SOURCES = ['Shapes', 'Photo'] as const;
type Source = (typeof SOURCES)[number];

function pairFor(source: Source): {
  from: MorphImageSource;
  to: MorphImageSource;
} {
  switch (source) {
    case 'Shapes':
      return { from: SHAPES.expo, to: SHAPES.react };
    case 'Photo':
      return PHOTOS;
  }
}

/* --------------------------------------------------------------------- app */

export default function App() {
  const t = useTheme();
  const [toggle, setToggle] = useState(false);
  const [auto, setAuto] = useState(false);
  const [source, setSource] = useState<Source>('Shapes');
  const [tint, setTint] = useState(0);
  const [blur, setBlur] = useState(60);
  const [duration, setDuration] = useState(700);

  // Tint only applies to the template-able shapes.
  const tintValue = source === 'Shapes' ? TINTS[tint]!.value : undefined;
  const { from, to } = pairFor(source);

  useEffect(() => {
    if (!auto) return;
    const id = setInterval(() => setToggle((v) => !v), duration + 700);
    return () => clearInterval(id);
  }, [auto, duration]);

  // Page-load: a gentle staggered fade + rise, SwiftUI `.transition`-style.
  const intro = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    Animated.timing(intro, {
      toValue: 1,
      duration: 650,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [intro]);
  const rise = (d: number) => ({
    opacity: intro,
    transform: [
      {
        translateY: intro.interpolate({
          inputRange: [0, 1],
          outputRange: [d, 0],
        }),
      },
    ],
  });

  return (
    <View style={[styles.root, { backgroundColor: t.groupedBg }]}>
      <StatusBar
        barStyle={t.dark ? 'light-content' : 'dark-content'}
        backgroundColor="transparent"
        translucent
      />
      <ScrollView
        contentContainerStyle={styles.scroll}
        showsVerticalScrollIndicator={false}
      >
        <Animated.View style={rise(8)}>
          <Text style={[styles.largeTitle, { color: t.label }]}>
            Morph View
          </Text>
          <Text style={[styles.lede, { color: t.secondary }]}>
            Gooey metaball morphing, powered by Metal on iOS and AGSL on
            Android.
          </Text>
        </Animated.View>

        <Animated.View style={rise(16)}>
          <Hero
            t={t}
            toggle={toggle}
            from={from}
            to={to}
            blur={blur}
            duration={duration}
            tint={tintValue}
            onPress={() => setToggle((v) => !v)}
          />
        </Animated.View>

        <Animated.View style={rise(20)}>
          <Section t={t} header="Source">
            <Segmented
              t={t}
              items={SOURCES as unknown as string[]}
              index={SOURCES.indexOf(source)}
              onChange={(i) => {
                LayoutAnimation.configureNext(
                  LayoutAnimation.create(
                    260,
                    LayoutAnimation.Types.easeInEaseOut,
                    LayoutAnimation.Properties.opacity
                  )
                );
                setSource(SOURCES[i]!);
              }}
            />
          </Section>
        </Animated.View>

        {source === 'Shapes' && (
          <Animated.View style={rise(24)}>
            <Section
              t={t}
              header="Tint"
              footer="Recolors template shapes, like an SF Symbol."
            >
              <Swatches t={t} index={tint} onChange={setTint} />
            </Section>
          </Animated.View>
        )}

        <Animated.View style={rise(28)}>
          <Section
            t={t}
            header="Adjustments"
            footer="Blur peaks at the midpoint of the morph — larger values look goopier."
          >
            <Row t={t} title="Auto-loop">
              <Switch
                value={auto}
                onValueChange={setAuto}
                trackColor={{ true: t.accent }}
              />
            </Row>
            <Row t={t} title="Blur radius" value={`${blur} pt`}>
              <Stepper
                t={t}
                onDec={() => setBlur((v) => Math.max(0, v - 4))}
                onInc={() => setBlur((v) => Math.min(60, v + 4))}
              />
            </Row>
            <Row t={t} title="Duration" value={`${duration} ms`} last>
              <Stepper
                t={t}
                onDec={() => setDuration((v) => Math.max(100, v - 100))}
                onInc={() => setDuration((v) => Math.min(2000, v + 100))}
              />
            </Row>
          </Section>
        </Animated.View>

        <Text style={[styles.footnote, { color: t.tertiary }]}>
          react-native-morph-view
        </Text>
      </ScrollView>
    </View>
  );
}

/* ------------------------------------------------------------------- hero */

function Hero({
  t,
  toggle,
  from,
  to,
  blur,
  duration,
  tint,
  onPress,
}: {
  t: Theme;
  toggle: boolean;
  from: MorphImageSource;
  to: MorphImageSource;
  blur: number;
  duration: number;
  tint?: ColorValue;
  onPress: () => void;
}) {
  const scale = useRef(new Animated.Value(1)).current;
  const to01 = (v: number) =>
    Animated.spring(scale, {
      toValue: v,
      useNativeDriver: true,
      speed: 40,
      bounciness: 6,
    }).start();

  return (
    <Pressable
      onPress={onPress}
      onPressIn={() => to01(0.97)}
      onPressOut={() => to01(1)}
    >
      <Animated.View
        style={[
          styles.hero,
          shadow(t),
          { backgroundColor: t.card, transform: [{ scale }] },
        ]}
      >
        <View style={[styles.canvas, { backgroundColor: t.canvasBottom }]}>
          <View
            style={[styles.canvasSheen, { backgroundColor: t.canvasTop }]}
          />
          <MorphView
            toggle={toggle}
            from={from}
            to={to}
            blurRadius={blur}
            duration={duration}
            tintColor={tint}
            style={styles.morph}
          />
        </View>
        <View style={styles.heroFooter}>
          <View style={[styles.dot, { backgroundColor: t.accent }]} />
          <Text style={[styles.heroHint, { color: t.secondary }]}>
            Tap to morph
          </Text>
        </View>
      </Animated.View>
    </Pressable>
  );
}

/* ------------------------------------------------- grouped list primitives */

function Section({
  t,
  header,
  footer,
  children,
}: {
  t: Theme;
  header?: string;
  footer?: string;
  children: React.ReactNode;
}) {
  return (
    <View style={styles.section}>
      {header ? (
        <Text style={[styles.sectionHeader, { color: t.secondary }]}>
          {header.toUpperCase()}
        </Text>
      ) : null}
      <View style={[styles.group, { backgroundColor: t.card }]}>
        {children}
      </View>
      {footer ? (
        <Text style={[styles.sectionFooter, { color: t.secondary }]}>
          {footer}
        </Text>
      ) : null}
    </View>
  );
}

function Row({
  t,
  title,
  value,
  last,
  children,
}: {
  t: Theme;
  title: string;
  value?: string;
  last?: boolean;
  children?: React.ReactNode;
}) {
  return (
    <View style={styles.rowWrap}>
      <View style={styles.row}>
        <Text style={[styles.rowTitle, { color: t.label }]}>{title}</Text>
        <View style={styles.rowTrailing}>
          {value ? (
            <Text style={[styles.rowValue, { color: t.secondary }]}>
              {value}
            </Text>
          ) : null}
          {children}
        </View>
      </View>
      {!last ? (
        <View style={[styles.rowSeparator, { backgroundColor: t.separator }]} />
      ) : null}
    </View>
  );
}

/* --------------------------------------------------- iOS segmented control */

function Segmented({
  t,
  items,
  index,
  onChange,
}: {
  t: Theme;
  items: string[];
  index: number;
  onChange: (i: number) => void;
}) {
  const [w, setW] = useState(0);
  const pos = useRef(new Animated.Value(index)).current;
  const seg = w > 0 ? (w - 4) / items.length : 0;

  useEffect(() => {
    Animated.spring(pos, {
      toValue: index,
      useNativeDriver: true,
      speed: 22,
      bounciness: 6,
    }).start();
  }, [index, pos]);

  const onLayout = (e: LayoutChangeEvent) => setW(e.nativeEvent.layout.width);

  return (
    <View style={styles.segmentWrap}>
      <View
        onLayout={onLayout}
        style={[styles.segmentTrack, { backgroundColor: t.fill }]}
      >
        {seg > 0 && (
          <Animated.View
            style={[
              styles.segmentThumb,
              shadow(t),
              {
                width: seg,
                backgroundColor: t.segmentThumb,
                transform: [{ translateX: Animated.multiply(pos, seg) }],
              },
            ]}
          />
        )}
        {items.map((item, i) => (
          <Pressable
            key={item}
            style={styles.segment}
            onPress={() => onChange(i)}
          >
            <Text
              style={[
                styles.segmentText,
                { color: t.label, opacity: i === index ? 1 : 0.6 },
                i === index && styles.segmentTextActive,
              ]}
            >
              {item}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

/* ----------------------------------------------------------- iOS stepper */

function Stepper({
  t,
  onDec,
  onInc,
}: {
  t: Theme;
  onDec: () => void;
  onInc: () => void;
}) {
  return (
    <View style={[styles.stepper, { backgroundColor: t.fill }]}>
      <Pressable
        style={({ pressed }) => [styles.stepperHalf, pressed && styles.pressed]}
        onPress={onDec}
        hitSlop={6}
      >
        <Text style={[styles.stepperGlyph, { color: t.label }]}>−</Text>
      </Pressable>
      <View style={[styles.stepperDivider, { backgroundColor: t.separator }]} />
      <Pressable
        style={({ pressed }) => [styles.stepperHalf, pressed && styles.pressed]}
        onPress={onInc}
        hitSlop={6}
      >
        <Text style={[styles.stepperGlyph, { color: t.label }]}>+</Text>
      </Pressable>
    </View>
  );
}

/* ----------------------------------------------------------- tint swatches */

function Swatches({
  t,
  index,
  onChange,
}: {
  t: Theme;
  index: number;
  onChange: (i: number) => void;
}) {
  return (
    <View style={styles.swatches}>
      {TINTS.map((item, i) => {
        const selected = i === index;
        const isOriginal = item.value === undefined;
        return (
          <Pressable
            key={item.name}
            onPress={() => onChange(i)}
            style={styles.swatchSlot}
          >
            <View
              style={[
                styles.swatchRing,
                { borderColor: selected ? t.accent : 'transparent' },
              ]}
            >
              <View
                style={[
                  styles.swatch,
                  isOriginal
                    ? {
                        backgroundColor: t.fill,
                        borderColor: t.separator,
                        borderWidth: StyleSheet.hairlineWidth,
                      }
                    : { backgroundColor: item.value as string },
                ]}
              >
                {isOriginal ? (
                  <View
                    style={[
                      styles.swatchSlash,
                      { backgroundColor: t.tertiary },
                    ]}
                  />
                ) : null}
              </View>
            </View>
            <Text
              style={[
                styles.swatchLabel,
                { color: selected ? t.label : t.secondary },
              ]}
            >
              {item.name}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

/* ------------------------------------------------------------------ shared */

function shadow(t: Theme) {
  return Platform.select({
    ios: {
      shadowColor: '#000',
      shadowOpacity: t.dark ? 0.4 : 0.08,
      shadowRadius: 14,
      shadowOffset: { width: 0, height: 8 },
    },
    android: { elevation: 3 },
  });
}

/* ------------------------------------------------------------------ styles */

const styles = StyleSheet.create({
  root: { flex: 1 },
  scroll: { paddingTop: 76, paddingBottom: 56, paddingHorizontal: 16 },

  largeTitle: {
    fontSize: 34,
    fontWeight: '700',
    letterSpacing: 0.37,
    marginHorizontal: 4,
  },
  lede: {
    fontSize: 15,
    lineHeight: 20,
    marginTop: 6,
    marginHorizontal: 4,
    marginBottom: 22,
  },

  hero: { borderRadius: 22, padding: 16, alignItems: 'center' },
  canvas: {
    width: '100%',
    height: 264,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  // A faint top-half wash to suggest the soft vertical gradient of iOS surfaces.
  canvasSheen: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    height: '55%',
    opacity: 0.5,
  },
  morph: { width: 200, height: 200 },
  heroFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    marginTop: 14,
    marginBottom: 2,
  },
  dot: { width: 6, height: 6, borderRadius: 3 },
  heroHint: { fontSize: 13, fontWeight: '500', letterSpacing: -0.08 },

  section: { marginTop: 26 },
  sectionHeader: {
    fontSize: 13,
    fontWeight: '400',
    letterSpacing: 0.5,
    marginLeft: 20,
    marginBottom: 7,
  },
  sectionFooter: {
    fontSize: 13,
    lineHeight: 18,
    marginTop: 7,
    marginHorizontal: 20,
  },
  group: { borderRadius: 12, overflow: 'hidden' },

  rowWrap: {},
  row: {
    minHeight: 48,
    paddingHorizontal: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  rowSeparator: { height: StyleSheet.hairlineWidth, marginLeft: 16 },
  rowTitle: { fontSize: 17, letterSpacing: -0.4, flexShrink: 1 },
  rowTrailing: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  rowValue: {
    fontSize: 17,
    letterSpacing: -0.4,
    fontVariant: ['tabular-nums'],
  },

  segmentWrap: { padding: 8 },
  segmentTrack: {
    height: 32,
    borderRadius: 9,
    flexDirection: 'row',
    padding: 2,
  },
  segmentThumb: {
    position: 'absolute',
    top: 2,
    bottom: 2,
    left: 2,
    borderRadius: 7,
  },
  segment: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  segmentText: { fontSize: 13, fontWeight: '500', letterSpacing: -0.08 },
  segmentTextActive: { fontWeight: '600' },

  stepper: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 32,
    width: 94,
    borderRadius: 8,
    overflow: 'hidden',
  },
  stepperHalf: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'stretch',
  },
  pressed: { opacity: 0.35 },
  stepperGlyph: { fontSize: 22, fontWeight: '400', marginTop: -2 },
  stepperDivider: { width: StyleSheet.hairlineWidth, height: 18 },

  swatches: {
    flexDirection: 'row',
    paddingVertical: 14,
    paddingHorizontal: 10,
    justifyContent: 'space-between',
  },
  swatchSlot: { alignItems: 'center', justifyContent: 'center', gap: 7 },
  swatchRing: {
    width: 42,
    height: 42,
    borderRadius: 21,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swatch: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  swatchSlash: { width: 30, height: 2, transform: [{ rotate: '-45deg' }] },
  swatchLabel: { fontSize: 11, letterSpacing: -0.08 },

  footnote: {
    fontSize: 13,
    textAlign: 'center',
    marginTop: 34,
    letterSpacing: -0.08,
  },
});
