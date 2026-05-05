@group(0) @binding(0) var<storage, read> a: array<vec4<i32>, 10>;
@group(0) @binding(1) var<storage, read> b: i32;

@compute
@workgroup_size(1)
fn f() {
  let c: i32 = a[a[b][b]][b];
}
