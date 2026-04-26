
const GLOBAL_0 = 0i;

@compute
@workgroup_size(1)
fn computeMain() {
  let c : i32 = 1;
  let d : i32 = 2 + GLOBAL_0;
  var e : i32 = d;
}