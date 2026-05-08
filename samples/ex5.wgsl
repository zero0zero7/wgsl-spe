@group(0) @binding(0) var<storage, read_write> output: array<i32, 2>;

@compute
@workgroup_size(1)
fn main() {
    output[1] = 4;
}
