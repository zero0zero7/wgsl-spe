@group(0) @binding(0) var<storage, read_write> result: vec2<f32>;
@group(0) @binding(1) var<uniform> a: vec2<f32>;
@group(0) @binding(2) var<storage> b: vec2<f32>;

@compute
@workgroup_size(4)
fn f() {
  result = a+b;
};