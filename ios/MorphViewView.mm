#import "MorphViewView.h"

#import <React/RCTConversions.h>

#import <react/renderer/components/MorphViewViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/MorphViewViewSpec/Props.h>
#import <react/renderer/components/MorphViewViewSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

// Generated header exposing the Swift `MorphHostView` to this Obj-C++ translation unit.
#import "MorphView-Swift.h"

using namespace facebook::react;

@implementation MorphViewView {
    MorphHostView * _host;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider
{
    return concreteComponentDescriptorProvider<MorphViewViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const MorphViewViewProps>();
    _props = defaultProps;

    _host = [[MorphHostView alloc] initWithFrame:frame];
    self.contentView = _host;
  }

  return self;
}

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps
{
    const auto &oldViewProps = *std::static_pointer_cast<MorphViewViewProps const>(_props);
    const auto &newViewProps = *std::static_pointer_cast<MorphViewViewProps const>(props);

    if (oldViewProps.fromUri != newViewProps.fromUri) {
        [_host morphSetFromUri:RCTNSStringFromStringNilIfEmpty(newViewProps.fromUri)];
    }
    if (oldViewProps.toUri != newViewProps.toUri) {
        [_host morphSetToUri:RCTNSStringFromStringNilIfEmpty(newViewProps.toUri)];
    }
    if (oldViewProps.toggle != newViewProps.toggle) {
        [_host morphSetToggle:newViewProps.toggle];
    }
    if (oldViewProps.blurRadius != newViewProps.blurRadius) {
        [_host morphSetBlurRadius:newViewProps.blurRadius];
    }
    if (oldViewProps.durationMs != newViewProps.durationMs) {
        [_host morphSetDurationMs:newViewProps.durationMs];
    }
    if (oldViewProps.tintColor != newViewProps.tintColor) {
        [_host morphSetTintColor:RCTUIColorFromSharedColor(newViewProps.tintColor)];
    }
    if (oldViewProps.morphBorderColor != newViewProps.morphBorderColor) {
        [_host morphSetBorderColor:RCTUIColorFromSharedColor(newViewProps.morphBorderColor)];
    }
    if (oldViewProps.morphBorderWidth != newViewProps.morphBorderWidth) {
        [_host morphSetBorderWidth:newViewProps.morphBorderWidth];
    }

    [super updateProps:props oldProps:oldProps];
}

@end

Class<RCTComponentViewProtocol> MorphViewViewCls(void)
{
    return MorphViewView.class;
}
