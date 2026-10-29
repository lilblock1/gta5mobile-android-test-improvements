// Nightlane Android: experimental presentation filters.
//
// Original GTA/web-port rendering and the wgpu translation layer remain
// intact. This module owns only the final surface shader on Android.
// The original nearest-pixel path is the default and safety fallback.
//
// AI-assisted fork enhancement: validate visuals and performance on real
// devices before treating any filter as a performance improvement.

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum PresentFilter {
    Native,
    Smooth,
}

impl PresentFilter {
    pub(crate) fn from_setting(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "smooth" => Self::Smooth,
            _ => Self::Native,
        }
    }

    pub(crate) fn current() -> Self {
        if cfg!(target_os = "android") {
            Self::from_setting(&std::env::var("NIGHTLANE_PRESENT").unwrap_or_default())
        } else {
            Self::Native
        }
    }

    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::Native => "native-nearest",
            Self::Smooth => "android-smooth-4tap",
        }
    }
}

// Keep the vertex stage identical between the original and experimental paths.
// The image is already rendered: no scene re-render, full-size intermediate
// render target, additional render pass or persistent texture is introduced.
const VERTEX: &str = r#"
@group(0) @binding(0) var src: texture_2d<f32>;
struct VOut {
    @builtin(position) position: vec4f,
    @location(0) uv: vec2f,
}
@vertex fn vs(@builtin(vertex_index) i: u32) -> VOut {
    var p = array<vec2f, 3>(vec2f(-1, -1), vec2f(3, -1), vec2f(-1, 3));
    var o: VOut;
    o.position = vec4f(p[i], 0, 1);
    o.uv = p[i] * vec2f(0.5, -0.5) + vec2f(0.5);
    return o;
}
"#;

const NATIVE_FRAGMENT: &str = r#"
@fragment fn fs(@location(0) uv: vec2f) -> @location(0) vec4f {
    let size = vec2i(textureDimensions(src));
    return textureLoad(src, clamp(vec2i(uv * vec2f(size)), vec2i(0), size - vec2i(1)), 0);
}
"#;

// Bilinear reconstruction using four textureLoad taps. This does not rely on
// the source render target being filterable or on an additional sampler.
// All accesses are clamped so upscale edges remain valid and so the original
// source format / binding layout can be preserved.
const SMOOTH_FRAGMENT: &str = r#"
@fragment fn fs(@location(0) uv: vec2f) -> @location(0) vec4f {
    let size = vec2i(textureDimensions(src));
    let last = size - vec2i(1);
    let src_position = uv * vec2f(size) - vec2f(0.5);
    let base = vec2i(floor(src_position));
    let fraction = fract(src_position);
    let a = textureLoad(src, clamp(base, vec2i(0), last), 0);
    let b = textureLoad(src, clamp(base + vec2i(1, 0), vec2i(0), last), 0);
    let c = textureLoad(src, clamp(base + vec2i(0, 1), vec2i(0), last), 0);
    let d = textureLoad(src, clamp(base + vec2i(1, 1), vec2i(0), last), 0);
    return mix(mix(a, b, fraction.x), mix(c, d, fraction.x), fraction.y);
}
"#;

// Build only the selected shader once per renderer/device. No per-frame shader
// compilation or dynamic pipeline toggles are introduced.
pub(crate) fn shader_source(filter: PresentFilter) -> String {
    let fragment = match filter {
        PresentFilter::Native => NATIVE_FRAGMENT,
        PresentFilter::Smooth => SMOOTH_FRAGMENT,
    };
    let mut source = String::with_capacity(VERTEX.len() + fragment.len() + 1);
    source.push_str(VERTEX);
    source.push('\n');
    source.push_str(fragment);
    source
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_modes_use_original_renderer() {
        assert_eq!(PresentFilter::from_setting(""), PresentFilter::Native);
        assert_eq!(PresentFilter::from_setting("unexpected"), PresentFilter::Native);
        assert_eq!(PresentFilter::from_setting(" native "), PresentFilter::Native);
        assert_eq!(PresentFilter::from_setting("SMOOTH"), PresentFilter::Smooth);
    }

    #[test]
    fn both_shader_paths_keep_the_same_surface_contract() {
        for filter in [PresentFilter::Native, PresentFilter::Smooth] {
            let shader = shader_source(filter);
            assert!(shader.contains("@vertex fn vs"));
            assert!(shader.contains("@fragment fn fs"));
            assert!(shader.contains("@group(0) @binding(0) var src: texture_2d<f32>"));
            assert!(shader.contains("textureLoad(src"));
        }
    }

    #[test]
    fn both_present_shaders_parse_and_validate_as_wgsl() {
        for filter in [PresentFilter::Native, PresentFilter::Smooth] {
            let source = shader_source(filter);
            let module = wgpu::naga::front::wgsl::parse_str(&source)
                .unwrap_or_else(|error| panic!("{}", error.emit_to_string(&source)));
            wgpu::naga::valid::Validator::new(
                wgpu::naga::valid::ValidationFlags::all(),
                wgpu::naga::valid::Capabilities::empty(),
            )
                .validate(&module)
                .unwrap_or_else(|error| panic!("{}", error.emit_to_string(&source)));
        }
    }

    #[test]
    fn smooth_path_is_clamped_and_needs_no_extra_texture() {
        let shader = shader_source(PresentFilter::Smooth);
        assert_eq!(shader.matches("textureLoad(src").count(), 4);
        assert!(shader.contains("clamp("));
        assert!(!shader.contains("textureSample("));
    }
}
