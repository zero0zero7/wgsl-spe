@group(0) @binding(0) var<storage, read_write> output: array<i32, 2>;

@compute
@workgroup_size(1)
fn main() {
  var a : i32 = 1;
  var b : i32 = 2;
  if (a == 1) {
    var c: i32 = 3;
    var d: i32 = 5;
    b = c + d;
  }
  var x = 1 + a;
  var y = 1 + b;
  output[0] = x;
  output[1] = y;
}
