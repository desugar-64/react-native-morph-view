import ImageIO
import SwiftUI
import UIKit

/// Observable model shared between the Fabric component (`MorphViewView.mm`) and the SwiftUI tree.
/// The `.mm` mutates these; SwiftUI re-renders.
final class MorphModel: ObservableObject {
    @Published var fromUri: String?
    @Published var toUri: String?
    @Published var toggle: Bool = false
    @Published var blurRadius: CGFloat = 24
    @Published var durationMs: CGFloat = 600
    @Published var tintColor: UIColor?
    /// The host view's size in points. Used to pick a decode resolution; quantized via
    /// `sizeBucket` so minor layout jitter doesn't trigger a re-decode.
    @Published var viewSize: CGSize = .zero

    /// Coarse size key (nearest 64pt) so image loads re-run only on a meaningful size change.
    var sizeBucket: Int {
        Int((max(viewSize.width, viewSize.height) / 64).rounded()) * 64
    }
}

/// UIView that hosts the SwiftUI gooey-morph effect. Bridged to Obj-C++ via the generated
/// `MorphView-Swift.h` header and driven entirely through `@objc` setters.
@objc public class MorphHostView: UIView {
    private let model = MorphModel()
    private var hosting: UIHostingController<MorphRootView>?

    /// Bundle that contains the compiled `default.metallib` (the alpha-threshold shader). The
    /// podspec ships the `.metal` in a "MorphViewShaders" resource bundle nested inside whichever
    /// bundle this class lives in; fall back to the class bundle for SPM / merged-framework setups.
    static let shaderBundle: Bundle = {
        let base = Bundle(for: MorphHostView.self)
        if let url = base.url(forResource: "MorphViewShaders", withExtension: "bundle"),
           let nested = Bundle(url: url) {
            return nested
        }
        return base
    }()

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    private func setup() {
        let root = MorphRootView(model: model)
        let controller = UIHostingController(rootView: root)
        controller.view.backgroundColor = .clear
        controller.view.translatesAutoresizingMaskIntoConstraints = false
        addSubview(controller.view)
        NSLayoutConstraint.activate([
            controller.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            controller.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            controller.view.topAnchor.constraint(equalTo: topAnchor),
            controller.view.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        hosting = controller
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        if model.viewSize != bounds.size {
            model.viewSize = bounds.size
        }
    }

    // MARK: - Prop setters (called from MorphViewView.mm)
    //
    // Prefixed with `morph…` so their Obj-C selectors can't collide with UIView's own
    // (e.g. `setTintColor:`), which would be a hard compile error.

    @objc public func morphSetFromUri(_ uri: NSString?) { model.fromUri = uri as String? }
    @objc public func morphSetToUri(_ uri: NSString?) { model.toUri = uri as String? }
    @objc public func morphSetToggle(_ on: Bool) { model.toggle = on }
    @objc public func morphSetBlurRadius(_ radius: CGFloat) { model.blurRadius = radius }
    @objc public func morphSetDurationMs(_ ms: CGFloat) { model.durationMs = ms }
    @objc public func morphSetTintColor(_ color: UIColor?) { model.tintColor = color }
}

/// Identity for an image-load task: re-decode when either the URI or the size bucket changes.
private struct TaskKey: Equatable {
    let uri: String?
    let bucket: Int
}

// MARK: - SwiftUI

/// Root view: crossfades two images and applies the morphing modifier. Directly mirrors the
/// `MorphingView` from the standalone Swift project, with content sourced from URIs.
struct MorphRootView: View {
    @ObservedObject var model: MorphModel
    @State private var animatedToggle: Bool = false
    @State private var fromImage: UIImage?
    @State private var toImage: UIImage?

    var body: some View {
        ZStack {
            if !animatedToggle {
                imageView(fromImage)
                    .transition(.opacity)
            }
            if animatedToggle {
                imageView(toImage)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .modifier(MorphingModifier(
            progress: animatedToggle ? 1 : 0,
            blurRadius: model.blurRadius
        ))
        .onAppear { animatedToggle = model.toggle }
        .onChange(of: model.toggle) { newValue in
            withAnimation(.easeInOut(duration: max(0.001, model.durationMs / 1000.0))) {
                animatedToggle = newValue
            }
        }
        .task(id: TaskKey(uri: model.fromUri, bucket: model.sizeBucket)) {
            fromImage = await loadImage(model.fromUri)
        }
        .task(id: TaskKey(uri: model.toUri, bucket: model.sizeBucket)) {
            toImage = await loadImage(model.toUri)
        }
    }

    @ViewBuilder
    private func imageView(_ image: UIImage?) -> some View {
        if let image {
            if let tint = model.tintColor {
                Image(uiImage: image.withRenderingMode(.alwaysTemplate))
                    .resizable()
                    .scaledToFit()
                    .foregroundColor(Color(tint))
            } else {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
            }
        } else {
            Color.clear
        }
    }

    private func loadImage(_ uri: String?) async -> UIImage? {
        guard let uri, let url = URL(string: uri) else { return nil }
        let data: Data?
        if url.isFileURL {
            data = try? Data(contentsOf: url)
        } else {
            data = try? await URLSession.shared.data(from: url).0
        }
        guard let data else { return nil }
        // Downsample at load time. The blur + alpha-threshold shader run on every animation
        // frame, so feeding them a full-resolution bitmap (e.g. a multi-megapixel photo) is the
        // cause of the lag — even when the view is only ~200pt. A thumbnail capped to the view's
        // pixel size keeps each frame cheap; the blur hides the loss of detail anyway.
        return Self.downsample(data: data, maxPixel: maxPixelSize) ?? UIImage(data: data)
    }

    /// Longest-side pixel cap for decoded images. Derived from the host view's size (in points)
    /// times the screen scale, with a sane floor/ceiling so it's useful before layout and never
    /// absurdly large.
    private var maxPixelSize: CGFloat {
        let scale = UIScreen.main.scale
        let side = max(model.viewSize.width, model.viewSize.height)
        let target = side > 1 ? side * scale : 512
        return min(max(target, 256), 1024)
    }

    /// Efficient ImageIO downsample — decodes straight to a thumbnail of the requested size
    /// without ever materializing the full-resolution bitmap in memory.
    private static func downsample(data: Data, maxPixel: CGFloat) -> UIImage? {
        let srcOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, srcOptions) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: cg)
    }
}

/// Animatable blur + gooey alpha-threshold modifier. `progress` animates; `blurRadius` does not.
fileprivate struct MorphingModifier: ViewModifier, Animatable {
    var progress: CGFloat
    var blurRadius: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content
                .compositingGroup()
                .blur(radius: blurProgress * blurRadius)
                .visualEffect { view, proxy in
                    view.layerEffect(
                        ShaderLibrary.bundle(MorphHostView.shaderBundle).alphaThreshold(),
                        maxSampleOffset: proxy.size
                    )
                }
        } else {
            // Pre-iOS 17 has no layerEffect: degrade to a plain blur crossfade.
            content
                .compositingGroup()
                .blur(radius: blurProgress * blurRadius)
        }
    }

    /// Reversible blur progress: 0 at both ends, peaks at 0.5 in the middle of the transition.
    private var blurProgress: CGFloat {
        progress > 0.5 ? abs(1.0 - progress) : progress
    }
}
