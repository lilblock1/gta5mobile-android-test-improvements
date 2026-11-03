//! A body-only fix from the supplied APK's shadow_cascade_fix.js, revalidated
//! against the bundled WGSL. Unknown/updated source is left untouched.
const BASE: &str = include_str!("shader_fixes/base_shadow.wgsl");
const SUBSTITUTIONS: &[(&str, &str)] = &[
    ("r1.w = bitcast<f32>(select(0u, 4294967295u, (r1.w < r1.z)));", "let gtaInsideCascade2 = r1.w < r1.z;"),
    ("let v_25 = (bitcast<u32>(r1.w) != 0u);", "let v_25 = gtaInsideCascade2;"),
    ("let v_26 = bitcast<f32>(v.tint_symbol_4[0i].x);", "let v_26 = v.tint_symbol_4[0i].x;"),
    ("r1.w = select(bitcast<f32>(v.tint_symbol_4[1i].x), v_26, v_25);", "let gtaCascade32 = select(v.tint_symbol_4[1i].x, v_26, v_25);"),
    ("r2.z = bitcast<f32>(select(0u, 4294967295u, (r2.z < r1.z)));", "let gtaInsideCascade1 = r2.z < r1.z;"),
    ("let v_28 = bitcast<u32>(r2.z);", "let v_28 = gtaInsideCascade1;"),
    ("r1.w = select(r1.w, bitcast<f32>(v.tint_symbol_4[2i].x), (v_28 != 0u));", "let gtaCascade21 = select(gtaCascade32, v.tint_symbol_4[2i].x, v_28);"),
    ("r1.z = bitcast<f32>(select(0u, 4294967295u, (r2.x < r1.z)));", "let gtaInsideCascade0 = r2.x < r1.z;"),
    ("let v_30 = bitcast<u32>(r1.z);", "let v_30 = gtaInsideCascade0;"),
    ("r1.z = select(r1.w, 0.0f, (v_30 != 0u));", "let gtaCascadeIndex = select(gtaCascade21, 0u, v_30);"),
    ("let v_31 = x0[bitcast<i32>(r1.z)];", "let v_31 = x0[gtaCascadeIndex];"),
    ("r1.z = f32(bitcast<i32>(r1.z));", "r1.z = f32(gtaCascadeIndex);"),
];

pub fn shadow_cascade(hash: &str, source: &str) -> Option<String> {
    if hash != "007089a30fcd7fd8" { return None; }
    let normalized = source.replace("\r\n", "\n");
    if normalized != BASE.replace("\r\n", "\n") { return None; }
    let mut fixed = normalized;
    for (before, after) in SUBSTITUTIONS {
        if fixed.matches(before).count() != 1 { return None; }
        fixed = fixed.replacen(before, after, 1);
    }
    let first = "  r2.x = (1.0f / r6.x);";
    let last = "  r2 = vec4<f32>(v_40.xy, r2.zw);";
    if fixed.matches(first).count() != 1 || fixed.matches(last).count() != 1 { return None; }
    let start = fixed.find(first)?;
    let end = fixed[start..].find(last)? + start + last.len();
    let guarded = format!("  let gtaRpdbScale = abs(r4.x * r5.x) + abs(r4.y * r5.y);\n  if (abs(r6.x) > max(1e-30f, gtaRpdbScale * 1e-6f)) {{\n{}\n  }} else {{ r2 = vec4<f32>(0.0f, 0.0f, r2.z, r2.w); }}", &fixed[start..end]);
    fixed.replace_range(start..end, &guarded);
    Some(fixed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use wgpu::naga;

    fn validate(source: &str) -> naga::Module {
        let module = naga::front::wgsl::parse_str(source)
            .unwrap_or_else(|e| panic!("{}", e.emit_to_string(source)));
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(), naga::valid::Capabilities::all())
            .validate(&module).unwrap();
        module
    }

    #[test]
    fn replacement_validates_and_keeps_bindings_and_entry_interface() {
        let patched = shadow_cascade("007089a30fcd7fd8", BASE).unwrap();
        let base = validate(BASE);
        let fixed = validate(&patched);
        let bindings = |m: &naga::Module| m.global_variables.iter()
            .filter_map(|(_, v)| v.binding.as_ref().map(|b| (b.group, b.binding, v.name.clone(), format!("{:?}", v.space))))
            .collect::<Vec<_>>();
        assert_eq!(bindings(&base), bindings(&fixed));
        let entries = |m: &naga::Module| m.entry_points.iter().map(|e|
            format!("{} {:?} {:?} {:?}", e.name, e.stage, e.function.arguments, e.function.result))
            .collect::<Vec<_>>();
        assert_eq!(entries(&base), entries(&fixed));
        assert!(patched.contains("x0[gtaCascadeIndex]"));
        assert!(!patched.contains("x0[bitcast<i32>(r1.z)]"));
        assert!(patched.find("if (abs(r6.x)").unwrap() < patched.find("1.0f / r6.x").unwrap());
    }

    #[test]
    fn unrelated_changed_and_already_patched_sources_are_preserved() {
        assert!(shadow_cascade("other", BASE).is_none());
        assert!(shadow_cascade("007089a30fcd7fd8", &(BASE.to_string()+"\n")).is_none());
        let patched = shadow_cascade("007089a30fcd7fd8", BASE).unwrap();
        assert!(shadow_cascade("007089a30fcd7fd8", &patched).is_none());
        assert_eq!(shadow_cascade("007089a30fcd7fd8", &BASE.replace("\n", "\r\n")), Some(patched));
    }
}
