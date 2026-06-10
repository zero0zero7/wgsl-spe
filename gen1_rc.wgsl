struct Struct_1 {
    a: bool,
    b: vec4<bool>,
    c: vec2<f32>,
    d: f32,
}

struct Struct_2 {
    a: vec2<bool>,
    b: Struct_1,
}

struct Struct_3 {
    a: Struct_1,
    b: vec3<u32>,
}

struct Struct_4 {
    a: f32,
    b: i32,
}

struct Struct_5 {
    a: vec2<f32>,
    b: vec3<i32>,
}

struct UniformBuffer {
    a: i32,
    b: vec2<i32>,
}

struct StorageBuffer {
    a: i32,
}

@group(0)
@binding(0)
var<uniform> u_input: UniformBuffer;

@group(0)
@binding(1)
var<storage, read_write> s_output: StorageBuffer;

var<private> global0: array<Struct_5, 23> = array<Struct_5, 23>(Struct_5(vec2<f32>(1620f, -1285f), vec3<i32>(i32(-2147483648), -48106i, 13322i)), Struct_5(vec2<f32>(2680f, 636f), vec3<i32>(0i, i32(-2147483648), 2147483647i)), Struct_5(vec2<f32>(-2028f, -1910f), vec3<i32>(i32(-2147483648), 4178i, 34304i)), Struct_5(vec2<f32>(-1503f, 1453f), vec3<i32>(2147483647i, 1i, 35022i)), Struct_5(vec2<f32>(-1293f, 730f), vec3<i32>(1i, 2081i, -14094i)), Struct_5(vec2<f32>(2315f, -997f), vec3<i32>(-1i, i32(-2147483648), 34165i)), Struct_5(vec2<f32>(216f, 592f), vec3<i32>(2147483647i, 38675i, -1854i)), Struct_5(vec2<f32>(305f, -347f), vec3<i32>(-5223i, 17822i, i32(-2147483648))), Struct_5(vec2<f32>(-934f, -1073f), vec3<i32>(-4946i, 57177i, i32(-2147483648))), Struct_5(vec2<f32>(-536f, -695f), vec3<i32>(1i, 2147483647i, -1i)), Struct_5(vec2<f32>(-2514f, -1051f), vec3<i32>(-46214i, i32(-2147483648), 45432i)), Struct_5(vec2<f32>(163f, 105f), vec3<i32>(2147483647i, 2147483647i, 2147483647i)), Struct_5(vec2<f32>(1000f, -2384f), vec3<i32>(74706i, -38796i, 0i)), Struct_5(vec2<f32>(1158f, 608f), vec3<i32>(-1i, -39106i, -56300i)), Struct_5(vec2<f32>(1047f, -2089f), vec3<i32>(18407i, 1i, 1i)), Struct_5(vec2<f32>(-712f, 535f), vec3<i32>(-1i, 30068i, 45899i)), Struct_5(vec2<f32>(511f, -1352f), vec3<i32>(0i, 1i, 2147483647i)), Struct_5(vec2<f32>(-1111f, -262f), vec3<i32>(-6702i, 4569i, 2147483647i)), Struct_5(vec2<f32>(714f, 147f), vec3<i32>(1i, 1i, 50823i)), Struct_5(vec2<f32>(-209f, 608f), vec3<i32>(i32(-2147483648), 2147483647i, 30495i)), Struct_5(vec2<f32>(-673f, 896f), vec3<i32>(-1i, 16276i, 41550i)), Struct_5(vec2<f32>(-1927f, -1038f), vec3<i32>(22363i, -35616i, -1i)), Struct_5(vec2<f32>(-1916f, 231f), vec3<i32>(2147483647i, 42111i, i32(-2147483648))));

var<private> global1: array<f32, 12> = array<f32, 12>(-415f, -1000f, 1130f, -442f, -147f, -1000f, -379f, 1329f, 1011f, 859f, -257f, -505f);

var<private> global2: array<vec3<bool>, 12> = array<vec3<bool>, 12>(vec3<bool>(false, true, false), vec3<bool>(false, false, true), vec3<bool>(true, false, false), vec3<bool>(true, false, false), vec3<bool>(false, false, true), vec3<bool>(false, true, true), vec3<bool>(true, false, false), vec3<bool>(false, true, false), vec3<bool>(true, false, true), vec3<bool>(false, false, false), vec3<bool>(true, false, false), vec3<bool>(true, false, true));

var<private> global3: vec3<i32> = vec3<i32>(0i, -1106i, 26684i);

var<private> global4: array<vec3<f32>, 26> = array<vec3<f32>, 26>(vec3<f32>(1000f, -2036f, -1077f), vec3<f32>(239f, -435f, -2850f), vec3<f32>(1086f, 298f, 831f), vec3<f32>(579f, 674f, 811f), vec3<f32>(-405f, -1600f, 139f), vec3<f32>(1014f, 1509f, 1000f), vec3<f32>(-424f, 324f, 139f), vec3<f32>(-269f, -1580f, 437f), vec3<f32>(891f, 397f, 614f), vec3<f32>(-1097f, 1679f, -909f), vec3<f32>(-232f, 544f, -816f), vec3<f32>(-1457f, -1000f, -261f), vec3<f32>(216f, -1000f, 316f), vec3<f32>(1115f, -855f, -292f), vec3<f32>(-362f, -1439f, -1614f), vec3<f32>(-1801f, -566f, 389f), vec3<f32>(-1778f, -2239f, -391f), vec3<f32>(-309f, -790f, 1696f), vec3<f32>(-1396f, -295f, -167f), vec3<f32>(-2068f, -1137f, 1000f), vec3<f32>(-378f, -209f, 472f), vec3<f32>(-246f, -464f, -185f), vec3<f32>(1543f, 1324f, 1226f), vec3<f32>(886f, -1000f, 281f), vec3<f32>(316f, -1801f, 731f), vec3<f32>(-241f, 914f, 1722f));

var<private> LOOP_COUNTERS: array<u32, 17>;

fn _wgslsmith_mod_vec4_i32(a: vec4<i32>, b: vec4<i32>) -> vec4<i32> {
    return select(a % b, a, (((((a[0i] < 0i)) || (b[0i] <= 0i)) || (((a[1i] < 0i)) || (b[1i] <= 0i))) || (((a[2i] < 0i)) || (b[2i] <= 0i))) || (((a[3i] < 0i)) || (b[3i] <= 0i)));
}

fn _wgslsmith_dot_vec3_i32(a: vec3<i32>, b: vec3<i32>) -> i32 {
    return dot(clamp(a, vec3<i32>(-26754i), vec3<i32>(26754i)), clamp(b, vec3<i32>(-26754i), vec3<i32>(26754i)));
}

fn _wgslsmith_mult_vec3_i32(a: vec3<i32>, b: vec3<i32>) -> vec3<i32> {
    return select(a * b, a, (((((a[0i] == -1i) && (b[0i] == i32(-2147483648))) || ((a[0i] == i32(-2147483648)) && (b[0i] == -1i))) || (((a[1i] == -1i) && (b[1i] == i32(-2147483648))) || ((a[1i] == i32(-2147483648)) && (b[1i] == -1i)))) || (((a[2i] == -1i) && (b[2i] == i32(-2147483648))) || ((a[2i] == i32(-2147483648)) && (b[2i] == -1i)))) || ((((b[0i] != 0i) && ((a[0i] > (2147483647i / b[0i])) || ((a[0i] < (i32(-2147483648) / b[0i]))))) || ((b[1i] != 0i) && ((a[1i] > (2147483647i / b[1i])) || ((a[1i] < (i32(-2147483648) / b[1i])))))) || ((b[2i] != 0i) && ((a[2i] > (2147483647i / b[2i])) || ((a[2i] < (i32(-2147483648) / b[2i])))))));
}

fn _wgslsmith_add_vec3_u32(a: vec3<u32>, b: vec3<u32>) -> vec3<u32> {
    return select(a + b, a, a > (vec3<u32>(4294967295u) - b));
}

fn _wgslsmith_div_vec2_u32(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    return select(a / b, a / vec2<u32>(2u), (b[0i] == 0u) || (b[1i] == 0u));
}

fn _wgslsmith_sub_vec3_u32(a: vec3<u32>, b: vec3<u32>) -> vec3<u32> {
    return select(a - b, a, (a < b));
}

fn _wgslsmith_div_vec3_i32(a: vec3<i32>, b: vec3<i32>) -> vec3<i32> {
    return select(a / b, a / vec3<i32>(2i), ((((a[0i] == i32(-2147483648)) && (b[0i] == -1i)) || (b[0i] == 0i)) || (((a[1i] == i32(-2147483648)) && (b[1i] == -1i)) || (b[1i] == 0i))) || (((a[2i] == i32(-2147483648)) && (b[2i] == -1i)) || (b[2i] == 0i)));
}

fn _wgslsmith_mult_vec2_u32(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    return select(a * b, a, ((b[0i] != 0u) && (a[0i] > (4294967295u / b[0i]))) || ((b[1i] != 0u) && (a[1i] > (4294967295u / b[1i]))));
}

fn _wgslsmith_dot_vec2_u32(a: vec2<u32>, b: vec2<u32>) -> u32 {
    return dot(clamp(a, vec2<u32>(0u), vec2<u32>(46340u)), clamp(b, vec2<u32>(0u), vec2<u32>(46340u)));
}

fn _wgslsmith_mult_vec4_i32(a: vec4<i32>, b: vec4<i32>) -> vec4<i32> {
    return select(a * b, a, ((((((a[0i] == -1i) && (b[0i] == i32(-2147483648))) || ((a[0i] == i32(-2147483648)) && (b[0i] == -1i))) || (((a[1i] == -1i) && (b[1i] == i32(-2147483648))) || ((a[1i] == i32(-2147483648)) && (b[1i] == -1i)))) || (((a[2i] == -1i) && (b[2i] == i32(-2147483648))) || ((a[2i] == i32(-2147483648)) && (b[2i] == -1i)))) || (((a[3i] == -1i) && (b[3i] == i32(-2147483648))) || ((a[3i] == i32(-2147483648)) && (b[3i] == -1i)))) || (((((b[0i] != 0i) && ((a[0i] > (2147483647i / b[0i])) || ((a[0i] < (i32(-2147483648) / b[0i]))))) || ((b[1i] != 0i) && ((a[1i] > (2147483647i / b[1i])) || ((a[1i] < (i32(-2147483648) / b[1i])))))) || ((b[2i] != 0i) && ((a[2i] > (2147483647i / b[2i])) || ((a[2i] < (i32(-2147483648) / b[2i])))))) || ((b[3i] != 0i) && ((a[3i] > (2147483647i / b[3i])) || ((a[3i] < (i32(-2147483648) / b[3i])))))));
}

fn _wgslsmith_div_vec2_f32(a: vec2<f32>, b: vec2<f32>) -> vec2<f32> {
    return select(vec2<f32>(42f), vec2<f32>(-123f), (abs(a[0i] / b[0i]) > abs(a[0i])) || (abs(a[1i] / b[1i]) > abs(a[1i])));
}

fn _wgslsmith_div_f32(a: f32, b: f32) -> f32 {
    return select(f32(42f), f32(-123f), abs(a / b) > abs(a));
}

fn _wgslsmith_dot_vec4_i32(a: vec4<i32>, b: vec4<i32>) -> i32 {
    return dot(clamp(a, vec4<i32>(-23170i), vec4<i32>(23170i)), clamp(b, vec4<i32>(-23170i), vec4<i32>(23170i)));
}

fn _wgslsmith_add_vec4_i32(a: vec4<i32>, b: vec4<i32>) -> vec4<i32> {
    return select(a + b, a, (((((b[0i] > 0i) && (a[0i] > (2147483647i - b[0i]))) || ((b[1i] > 0i) && (a[1i] > (2147483647i - b[1i])))) || ((b[2i] > 0i) && (a[2i] > (2147483647i - b[2i])))) || ((b[3i] > 0i) && (a[3i] > (2147483647i - b[3i])))) || ((((((b[0i] < 0i)) && ((a[0i] < (i32(-2147483648) - b[0i])))) || (((b[1i] < 0i)) && ((a[1i] < (i32(-2147483648) - b[1i]))))) || (((b[2i] < 0i)) && ((a[2i] < (i32(-2147483648) - b[2i]))))) || (((b[3i] < 0i)) && ((a[3i] < (i32(-2147483648) - b[3i]))))));
}

fn _wgslsmith_mult_i32(a: i32, b: i32) -> i32 {
    return select(a * b, a, (((a == -1i) && (b == i32(-2147483648))) || ((a == i32(-2147483648)) && (b == -1i))) || ((b != 0i) && ((a > (2147483647i / b)) || ((a < (i32(-2147483648) / b))))));
}

fn _wgslsmith_add_i32(a: i32, b: i32) -> i32 {
    return select(a + b, a, ((b > 0i) && (a > (2147483647i - b))) || (((b < 0i)) && ((a < (i32(-2147483648) - b)))));
}

fn _wgslsmith_sub_i32(a: i32, b: i32) -> i32 {
    return select(a - b, a, (((b < 0i)) && (a > (2147483647i + b))) || ((b > 0i) && ((a < (i32(-2147483648) + b)))));
}

fn _wgslsmith_add_u32(a: u32, b: u32) -> u32 {
    return select(a + b, a, a > (u32(4294967295u) - b));
}

fn _wgslsmith_sub_u32(a: u32, b: u32) -> u32 {
    return select(a - b, a, (a < b));
}

fn _wgslsmith_f_op_vec4_f32(v: vec4<f32>) -> vec4<f32> {
    return select(v, vec4<f32>(10f), any((abs(v) < vec4<f32>(0.1f))) || any(abs(v) >= vec4<f32>(16777216f)));
}

fn _wgslsmith_add_vec2_i32(a: vec2<i32>, b: vec2<i32>) -> vec2<i32> {
    return select(a + b, a, (((b[0i] > 0i) && (a[0i] > (2147483647i - b[0i]))) || ((b[1i] > 0i) && (a[1i] > (2147483647i - b[1i])))) || ((((b[0i] < 0i)) && ((a[0i] < (i32(-2147483648) - b[0i])))) || (((b[1i] < 0i)) && ((a[1i] < (i32(-2147483648) - b[1i]))))));
}

fn _wgslsmith_mod_vec3_i32(a: vec3<i32>, b: vec3<i32>) -> vec3<i32> {
    return select(a % b, a, ((((a[0i] < 0i)) || (b[0i] <= 0i)) || (((a[1i] < 0i)) || (b[1i] <= 0i))) || (((a[2i] < 0i)) || (b[2i] <= 0i)));
}

fn _wgslsmith_sub_vec2_u32(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    return select(a - b, a, (a < b));
}

fn _wgslsmith_f_op_f32(v: f32) -> f32 {
    return select(v, f32(10f), ((abs(v) < f32(0.1f))) || (abs(v) >= f32(16777216f)));
}

fn _wgslsmith_div_u32(a: u32, b: u32) -> u32 {
    return select(a / b, a / u32(2u), b == 0u);
}

fn _wgslsmith_sub_vec3_i32(a: vec3<i32>, b: vec3<i32>) -> vec3<i32> {
    return select(a - b, a, (((((b[0i] < 0i)) && (a[0i] > (2147483647i + b[0i]))) || (((b[1i] < 0i)) && (a[1i] > (2147483647i + b[1i])))) || (((b[2i] < 0i)) && (a[2i] > (2147483647i + b[2i])))) || ((((b[0i] > 0i) && ((a[0i] < (i32(-2147483648) + b[0i])))) || ((b[1i] > 0i) && ((a[1i] < (i32(-2147483648) + b[1i]))))) || ((b[2i] > 0i) && ((a[2i] < (i32(-2147483648) + b[2i]))))));
}

fn _wgslsmith_mod_u32(a: u32, b: u32) -> u32 {
    return select(a % b, a, b == 0u);
}

fn _wgslsmith_clamp_vec3_u32(e: vec3<u32>, low: vec3<u32>, high: vec3<u32>) -> vec3<u32> {
    return select(clamp(e, low, high), clamp(e, high, low), low > high);
}

fn _wgslsmith_mod_i32(a: i32, b: i32) -> i32 {
    return select(a % b, a, ((a < 0i)) || (b <= 0i));
}

fn _wgslsmith_mod_vec3_u32(a: vec3<u32>, b: vec3<u32>) -> vec3<u32> {
    return select(a % b, a, ((b[0i] == 0u) || (b[1i] == 0u)) || (b[2i] == 0u));
}

fn _wgslsmith_div_vec3_u32(a: vec3<u32>, b: vec3<u32>) -> vec3<u32> {
    return select(a / b, a / vec3<u32>(2u), ((b[0i] == 0u) || (b[1i] == 0u)) || (b[2i] == 0u));
}

fn _wgslsmith_clamp_i32(e: i32, low: i32, high: i32) -> i32 {
    return select(clamp(e, low, high), clamp(e, high, low), low > high);
}

fn _wgslsmith_add_vec2_u32(a: vec2<u32>, b: vec2<u32>) -> vec2<u32> {
    return select(a + b, a, a > (vec2<u32>(4294967295u) - b));
}

fn _wgslsmith_f_op_vec3_f32(v: vec3<f32>) -> vec3<f32> {
    return select(v, vec3<f32>(10f), any((abs(v) < vec3<f32>(0.1f))) || any(abs(v) >= vec3<f32>(16777216f)));
}

fn _wgslsmith_mult_u32(a: u32, b: u32) -> u32 {
    return select(a * b, a, (b != 0u) && (a > (4294967295u / b)));
}

fn _wgslsmith_f_op_vec2_f32(v: vec2<f32>) -> vec2<f32> {
    return select(v, vec2<f32>(10f), any((abs(v) < vec2<f32>(0.1f))) || any(abs(v) >= vec2<f32>(16777216f)));
}

fn _wgslsmith_dot_vec3_u32(a: vec3<u32>, b: vec3<u32>) -> u32 {
    return dot(clamp(a, vec3<u32>(0u), vec3<u32>(37837u)), clamp(b, vec3<u32>(0u), vec3<u32>(37837u)));
}

fn _wgslsmith_dot_vec2_i32(a: vec2<i32>, b: vec2<i32>) -> i32 {
    return dot(clamp(a, vec2<i32>(-32767i), vec2<i32>(32767i)), clamp(b, vec2<i32>(-32767i), vec2<i32>(32767i)));
}

fn _wgslsmith_clamp_u32(e: u32, low: u32, high: u32) -> u32 {
    return select(clamp(e, low, high), clamp(e, high, low), low > high);
}

fn _wgslsmith_index_u32(index: u32, size: u32) -> u32 {
    return index % size;
}

fn _wgslsmith_dot_vec4_u32(a: vec4<u32>, b: vec4<u32>) -> u32 {
    return dot(clamp(a, vec4<u32>(0u), vec4<u32>(32767u)), clamp(b, vec4<u32>(0u), vec4<u32>(32767u)));
}

fn func_6() -> bool {
    if (select(true, all(global2[_wgslsmith_index_u32(select(1u, ~_wgslsmith_mult_u32(4294967295u, 35265u), !(global3.x >= u_input.b.x)), 12u)]), all(!(!global2[_wgslsmith_index_u32(4294967295u >> (1u % 32u), 12u)])))) {
    }
    if (global1[_wgslsmith_index_u32(~_wgslsmith_mult_u32(_wgslsmith_dot_vec4_u32(vec4<u32>(1u, 1u, 1u, 1u), vec4<u32>(1u, 1u, 1u, 1u)), 34114u), 12u)] == 269f) {
        var var_0 = Struct_5(_wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(min(_wgslsmith_f_op_vec2_f32(vec2<f32>(-406f, global1[_wgslsmith_index_u32(4294967295u, 12u)]) * vec2<f32>(1000f, global1[_wgslsmith_index_u32(0u, 12u)])), _wgslsmith_div_vec2_f32(vec2<f32>(-985f, -217f), vec2<f32>(893f, global1[_wgslsmith_index_u32(1u, 12u)])))))), -_wgslsmith_mod_vec3_i32(vec3<i32>(~0i, i32(-2147483648), 2147483647i), vec3<i32>(u_input.a, global3.x, 0i)));
        let var_1 = true;
        let var_2 = -1000f;
        switch (_wgslsmith_add_i32(_wgslsmith_dot_vec2_i32(_wgslsmith_add_vec2_i32(vec2<i32>(global3.x, 0i), global3.zz) << (~vec2<u32>(29337u, 106011u) % vec2<u32>(32u)), vec2<i32>(u_input.a, firstLeadingBit(-38381i))) & firstLeadingBit(_wgslsmith_add_i32(0i, firstTrailingBit(66980i))), i32(-1i) * -14093i)) {
            case 0i: {
                var var_3 = ~vec4<u32>(0u, 1u, _wgslsmith_add_u32(~firstTrailingBit(49079u), 0u), 0u);
                var var_4 = _wgslsmith_f_op_f32(sign(-931f));
                let var_5 = Struct_2(!select(select(vec2<bool>(var_1, var_1), vec2<bool>(var_1, var_1), !vec2<bool>(var_1, false)), vec2<bool>(var_0.a.x > var_0.a.x, true), vec2<bool>(true, !var_1)), Struct_1(false, vec4<bool>(!(!var_1), true, any(vec4<bool>(var_1, true, var_1, var_1)), var_1 & any(vec2<bool>(true, var_1))), _wgslsmith_f_op_vec2_f32(-vec2<f32>(_wgslsmith_f_op_f32(round(-432f)), 472f)), _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(f32(-1f) * -693f)) - _wgslsmith_f_op_f32(sign(_wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(var_3.x, 12u)] * global1[_wgslsmith_index_u32(var_3.x, 12u)]))))));
                let var_6 = ~4294967295u & ~(~(~max(var_3.x, var_3.x)));
                global1 = array<f32, 12>();
            }
            default: {
                let var_3 = global0[_wgslsmith_index_u32(_wgslsmith_dot_vec2_u32(vec2<u32>(25070u, 27703u), countOneBits(vec2<u32>(1u, 1u))), 23u)];
                let var_4 = Struct_3(Struct_1(all(global2[_wgslsmith_index_u32(~(~1u), 12u)]), !select(vec4<bool>(true, false, var_1, false), vec4<bool>(false, var_1, true, var_1), !vec4<bool>(var_1, var_1, true, true)), _wgslsmith_f_op_vec2_f32(floor(var_0.a)), var_0.a.x), ~vec3<u32>(1u, 1u, 1u));
                global4 = array<vec3<f32>, 26>();
                let var_5 = Struct_2(select(select(var_4.a.b.yw, !(!vec2<bool>(false, var_1)), !var_1), select(var_4.a.b.zy, var_4.a.b.xz, var_1), select(select(!vec2<bool>(var_4.a.b.x, var_4.a.b.x), var_4.a.b.zx, !var_1), !(!var_4.a.b.yx), !(23203u > var_4.b.x))), var_4.a);
            }
        }
    }
    if (!((~_wgslsmith_dot_vec4_i32(-vec4<i32>(17393i, u_input.a, 2147483647i, i32(-2147483648)), -vec4<i32>(global3.x, -1i, 2147483647i, 0i)) < (-(~global3.x) >> (1u % 32u))))) {
        for (var var_0 = -45196i; true; global2 = array<vec3<bool>, 12>()) {
            if (LOOP_COUNTERS[0u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[0u] = LOOP_COUNTERS[0u] + 1u;
            break;
        }
        switch (global3.x) {
            case 0i: {
                var var_0 = Struct_1(!(-1261f > _wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32(_wgslsmith_mult_u32(15005u, 20850u), 12u)])), vec4<bool>(true, true, true, true), _wgslsmith_f_op_vec2_f32(min(_wgslsmith_f_op_vec2_f32(-vec2<f32>(_wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(1u, 12u)] * global1[_wgslsmith_index_u32(4294967295u, 12u)]), _wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32(4294967295u, 12u)]))), vec2<f32>(_wgslsmith_f_op_f32(max(global1[_wgslsmith_index_u32(_wgslsmith_mod_u32(76366u, 137509u), 12u)], _wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32(59260u, 12u)]))), global1[_wgslsmith_index_u32(~_wgslsmith_add_u32(4294967295u, 1u), 12u)]))), _wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32(0u, 12u)]));
                var var_1 = _wgslsmith_div_vec3_u32(firstLeadingBit(~vec3<u32>(_wgslsmith_dot_vec4_u32(vec4<u32>(6328u, 0u, 158141u, 1u), vec4<u32>(4294967295u, 0u, 1u, 888u)), _wgslsmith_add_u32(4294967295u, 0u), 0u)), select(_wgslsmith_mod_vec3_u32(reverseBits(~vec3<u32>(4294967295u, 0u, 1u)), vec3<u32>(1u, _wgslsmith_add_u32(52041u, 12434u), 82917u)), vec3<u32>(~_wgslsmith_div_u32(4294967295u, 46381u), firstLeadingBit(_wgslsmith_add_u32(0u, 0u)), select(55711u, 1u, var_0.b.x) & _wgslsmith_div_u32(10367u, 11719u)), vec3<bool>(all(select(var_0.b.yy, vec2<bool>(true, true), var_0.b.zx)), any(!vec3<bool>(true, false, var_0.a)), !(!var_0.a))));
                return !((_wgslsmith_f_op_f32(max(1436f, _wgslsmith_f_op_f32(trunc(var_0.d)))) <= 1448f) != all(select(var_0.b.yx, vec2<bool>(true, false), select(vec2<bool>(var_0.a, false), var_0.b.xz, true))));
            }
            default: {
                var var_0 = vec2<u32>(_wgslsmith_dot_vec2_u32(~select(~vec2<u32>(48467u, 67112u), select(vec2<u32>(95461u, 59089u), vec2<u32>(1u, 99683u), vec2<bool>(true, false)), vec2<bool>(true, false)), ~vec2<u32>(0u, 5810u)), _wgslsmith_div_u32(_wgslsmith_div_u32(abs(_wgslsmith_add_u32(47113u, 0u)), ~min(15102u, 95804u)), 97262u));
                global4 = array<vec3<f32>, 26>();
                global4 = array<vec3<f32>, 26>();
                global4 = array<vec3<f32>, 26>();
            }
        }
    }
    var var_0 = Struct_5(vec2<f32>(1f, _wgslsmith_f_op_f32(floor(_wgslsmith_f_op_f32(min(660f, _wgslsmith_f_op_f32(f32(-1f) * -725f)))))), reverseBits(-(~vec3<i32>(global3.x, 31594i, 38088i))));
    global2 = array<vec3<bool>, 12>();
    return !(!(!any(global2[_wgslsmith_index_u32(44714u, 12u)])) && false);
}

fn func_5(arg_0: vec3<u32>) -> Struct_4 {
    if (true) {
        if (false) {
            global2 = array<vec3<bool>, 12>();
        }
    }
    let var_0 = _wgslsmith_mod_vec4_i32(~(_wgslsmith_mult_vec4_i32(~vec4<i32>(u_input.b.x, u_input.b.x, global3.x, 50326i), firstLeadingBit(vec4<i32>(global3.x, -19015i, global3.x, global3.x))) | ~(-vec4<i32>(global3.x, global3.x, global3.x, u_input.b.x))), ~vec4<i32>(6400i, ~2147483647i, 1i, _wgslsmith_mult_i32(-u_input.a, 0i)));
    global4 = array<vec3<f32>, 26>();
    if (true) {
        var var_1 = _wgslsmith_f_op_f32(ceil(_wgslsmith_div_f32(_wgslsmith_f_op_f32(557f - _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32(arg_0.x, 12u)]))), 2447f)));
        switch (_wgslsmith_dot_vec3_i32(var_0.xyw, firstTrailingBit(~vec3<i32>(-13426i, i32(-1i) * -47447i, global3.x)))) {
            default: {
                let var_2 = Struct_2(vec2<bool>(true, true), Struct_1(true, select(vec4<bool>(true, true, true, true), vec4<bool>(true, true, true, true), !((global1[_wgslsmith_index_u32(4294967295u, 12u)] < 765f))), _wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(-vec2<f32>(global1[_wgslsmith_index_u32(arg_0.x, 12u)], global1[_wgslsmith_index_u32(0u, 12u)])) - vec2<f32>(576f, global1[_wgslsmith_index_u32(115786u, 12u)]))), _wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32((arg_0.x | arg_0.x) | 92333u, 12u)])));
                let var_3 = true;
                var var_4 = Struct_3(Struct_1(func_6(), !vec4<bool>(var_2.b.a, all(global2[_wgslsmith_index_u32(arg_0.x, 12u)]), true, !var_2.a.x), _wgslsmith_f_op_vec2_f32(max(_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(select(vec2<f32>(-1390f, var_2.b.c.x), var_2.b.c, var_3)) * _wgslsmith_f_op_vec2_f32(step(vec2<f32>(global1[_wgslsmith_index_u32(4294967295u, 12u)], 402f), vec2<f32>(1000f, -1055f)))), var_2.b.c)), _wgslsmith_f_op_f32(f32(-1f) * -651f)), _wgslsmith_sub_vec3_u32(~select(vec3<u32>(arg_0.x, arg_0.x, arg_0.x), arg_0 << (arg_0 % vec3<u32>(32u)), global2[_wgslsmith_index_u32(_wgslsmith_add_u32(12223u, arg_0.x), 12u)]), arg_0));
                let var_5 = false;
                global4 = array<vec3<f32>, 26>();
            }
        }
        loop {
            if (LOOP_COUNTERS[1u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[1u] = LOOP_COUNTERS[1u] + 1u;
            var_1 = global1[_wgslsmith_index_u32(1u, 12u)];
        }
    }
    var var_1 = arg_0;
    return Struct_4(-1588f, var_0.x);
}

fn func_4(arg_0: vec2<f32>) -> Struct_1 {
    var var_0 = _wgslsmith_f_op_vec3_f32(-_wgslsmith_f_op_vec3_f32(abs(_wgslsmith_f_op_vec3_f32(vec3<f32>(_wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(11226u, 12u)] * arg_0.x), _wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(19820u, 12u)] + -1000f), -556f) * _wgslsmith_f_op_vec3_f32(vec3<f32>(global1[_wgslsmith_index_u32(13219u, 12u)], -1188f, -799f) + _wgslsmith_f_op_vec3_f32(-global4[_wgslsmith_index_u32(106125u, 26u)]))))));
    var var_1 = Struct_4(_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-arg_0.x)), ~(~global3.x));
    var_1 = func_5(~vec3<u32>(1u, 1u, 1u));
    let var_2 = ~(~vec4<u32>(1u, 1u, 1u, 1u));
    return Struct_1(true, vec4<bool>(func_6(), any(select(vec3<bool>(true, true, true), select(vec3<bool>(false, false, true), global2[_wgslsmith_index_u32(var_2.x, 12u)], false), select(global2[_wgslsmith_index_u32(26571u, 12u)], global2[_wgslsmith_index_u32(var_2.x, 12u)], false))), all(vec4<bool>(true, true, true, true)), all(select(global2[_wgslsmith_index_u32(var_2.x & 0u, 12u)], vec3<bool>(true, true, true), (var_2.x < 38650u)))), _wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(max(_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(vec2<f32>(2088f, 627f) - vec2<f32>(-1364f, var_0.x)) - vec2<f32>(global1[_wgslsmith_index_u32(var_2.x, 12u)], -1739f)), _wgslsmith_f_op_vec2_f32(select(_wgslsmith_f_op_vec2_f32(-vec2<f32>(var_0.x, var_1.a)), arg_0, vec2<bool>(false, true))))) - vec2<f32>(355f, 307f)), _wgslsmith_f_op_f32(ceil(1000f)));
}

fn func_3(arg_0: f32, arg_1: vec4<bool>, arg_2: bool, arg_3: Struct_1) -> vec4<bool> {
    global2 = array<vec3<bool>, 12>();
    let var_0 = func_4(arg_3.c);
    if (true) {
        loop {
            if (LOOP_COUNTERS[2u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[2u] = LOOP_COUNTERS[2u] + 1u;
            let var_1 = Struct_2(var_0.b.yx, arg_3);
            continue;
        }
        global4 = array<vec3<f32>, 26>();
        if (_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(f32(-1f) * -160f))) <= _wgslsmith_f_op_f32(-arg_3.c.x)) {
        }
        for (var var_1 = 20242i; ; global4 = array<vec3<f32>, 26>()) {
            if (LOOP_COUNTERS[3u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[3u] = LOOP_COUNTERS[3u] + 1u;
            continue;
        }
        var var_1 = Struct_3(func_4(_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(-arg_3.c) - _wgslsmith_f_op_vec2_f32(trunc(vec2<f32>(global1[_wgslsmith_index_u32(1u, 12u)], 956f))))), _wgslsmith_div_vec3_u32(vec3<u32>(1u, 1u, 1u), vec3<u32>(reverseBits(74615u), _wgslsmith_dot_vec3_u32(vec3<u32>(1u, 38633u, 0u), vec3<u32>(63383u, 25397u, 92819u)), 1u)));
    }
    if (!arg_1.x) {
        global3 = ~(~(~abs(vec3<i32>(-1874i, global3.x, global3.x) << (vec3<u32>(4294967295u, 0u, 4294967295u) % vec3<u32>(32u)))));
        if (false) {
            var var_1 = Struct_4(_wgslsmith_f_op_f32(-global1[_wgslsmith_index_u32(min(_wgslsmith_mult_u32(4294967295u, _wgslsmith_add_u32(18553u, 56701u)), countOneBits(min(0u, 44829u))), 12u)]), min(2147483647i, ~(abs(global3.x) << (1u % 32u))));
            var var_2 = true;
            let var_3 = -abs(-firstLeadingBit(vec4<i32>(7008i, var_1.b, -1i, u_input.b.x))) >> (~vec4<u32>(reverseBits(12448u), ~abs(79504u), ~11219u >> (~4294967295u % 32u), _wgslsmith_clamp_u32(_wgslsmith_sub_u32(12233u, 0u), 1u, 1u)) % vec4<u32>(32u));
        }
        global4 = array<vec3<f32>, 26>();
        var var_1 = func_5(vec3<u32>(countOneBits(~_wgslsmith_dot_vec3_u32(vec3<u32>(0u, 4294967295u, 4294967295u), vec3<u32>(0u, 0u, 67970u))), abs(1u), _wgslsmith_sub_u32(abs(4294967295u), _wgslsmith_mult_u32(_wgslsmith_div_u32(4294967295u, 7378u), ~50975u))));
    }
    let var_1 = vec4<f32>(_wgslsmith_f_op_f32(ceil(arg_3.c.x)), _wgslsmith_f_op_f32(-arg_3.c.x), -1000f, _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(sign(arg_0))));
    return select(arg_1, func_4(_wgslsmith_f_op_vec2_f32(vec2<f32>(_wgslsmith_f_op_f32(-881f * global1[_wgslsmith_index_u32(4294967295u, 12u)]), 180f) * _wgslsmith_f_op_vec2_f32(abs(_wgslsmith_f_op_vec2_f32(step(arg_3.c, vec2<f32>(arg_3.d, var_1.x))))))).b, all(arg_3.b));
}

fn func_7(arg_0: Struct_2, arg_1: vec2<u32>) -> Struct_1 {
    loop {
        if (LOOP_COUNTERS[4u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[4u] = LOOP_COUNTERS[4u] + 1u;
        var var_0 = Struct_2(func_4(_wgslsmith_div_vec2_f32(arg_0.b.c, _wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(-vec2<f32>(1201f, arg_0.b.c.x)) + _wgslsmith_f_op_vec2_f32(-vec2<f32>(global1[_wgslsmith_index_u32(arg_1.x, 12u)], arg_0.b.d))))).b.yy, Struct_1(!(_wgslsmith_f_op_f32(arg_0.b.c.x * -356f) > _wgslsmith_div_f32(global1[_wgslsmith_index_u32(1788u, 12u)], global1[_wgslsmith_index_u32(60490u, 12u)])), arg_0.b.b, arg_0.b.c, _wgslsmith_f_op_f32(-arg_0.b.c.x)));
        switch (u_input.b.x) {
            case -1i: {
                global4 = array<vec3<f32>, 26>();
                break;
            }
            case -7518i: {
            }
            default: {
                global3 = vec3<i32>(64527i & u_input.a, _wgslsmith_clamp_i32(global3.x, ~u_input.a, 0i), ~u_input.a);
                break;
            }
        }
        break;
    }
    var var_0 = u_input.a;
    global3 = vec3<i32>(-39063i, u_input.b.x, u_input.b.x);
    return Struct_1(((~arg_1.x ^ 1u) == ~_wgslsmith_add_u32(arg_1.x, arg_1.x)) & !arg_0.a.x, select(vec4<bool>(any(func_4(arg_0.b.c).b.yx), all(!arg_0.b.b), true, !(arg_0.a.x | arg_0.b.b.x)), vec4<bool>(any(select(arg_0.a, vec2<bool>(arg_0.b.a, arg_0.a.x), false)), true, arg_0.a.x, all(func_4(arg_0.b.c).b)), !(!all(arg_0.b.b.xw))), _wgslsmith_f_op_vec2_f32(floor(vec2<f32>(arg_0.b.d, -120f))), _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(_wgslsmith_f_op_f32(_wgslsmith_f_op_f32(-arg_0.b.d) + arg_0.b.c.x) * global1[_wgslsmith_index_u32(~(~arg_1.x), 12u)]) * _wgslsmith_f_op_f32(f32(-1f) * -1397f)));
}

fn func_2(arg_0: vec2<f32>) -> f32 {
    loop {
        if (LOOP_COUNTERS[5u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[5u] = LOOP_COUNTERS[5u] + 1u;
        var var_0 = any(vec2<bool>(true, true));
        global0 = array<Struct_5, 23>();
        for (var var_1: i32; var_1 != 10947i; var_1 += 1i) {
            if (LOOP_COUNTERS[6u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[6u] = LOOP_COUNTERS[6u] + 1u;
            var var_2 = _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-1f)));
            break;
        }
    }
    for (; false; ) {
        if (LOOP_COUNTERS[7u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[7u] = LOOP_COUNTERS[7u] + 1u;
        var var_0 = func_7(Struct_2(vec2<bool>(true, true), Struct_1(false, select(func_3(-1560f, vec4<bool>(false, true, false, true), false, Struct_1(true, vec4<bool>(true, true, false, false), vec2<f32>(318f, global1[_wgslsmith_index_u32(60686u, 12u)]), 102f)), vec4<bool>(true, true, true, true), vec4<bool>(true, false, false, true)), vec2<f32>(arg_0.x, _wgslsmith_f_op_f32(max(arg_0.x, global1[_wgslsmith_index_u32(4294967295u, 12u)]))), _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-arg_0.x)))), reverseBits(~vec2<u32>(1u, 1u)));
        global3 = -firstTrailingBit(vec3<i32>(u_input.a, -36612i, _wgslsmith_clamp_i32(abs(global3.x), global3.x, _wgslsmith_add_i32(-32200i, -25874i))));
        if (any(vec4<bool>(!var_0.b.x, true, true, all(vec2<bool>(true, true))))) {
            global1 = array<f32, 12>();
            global4 = array<vec3<f32>, 26>();
            global2 = array<vec3<bool>, 12>();
            var var_1 = _wgslsmith_clamp_u32(~16751u, 1u, abs(48800u));
        }
        switch (_wgslsmith_add_i32(global3.x, firstTrailingBit(_wgslsmith_dot_vec4_i32(_wgslsmith_add_vec4_i32(~vec4<i32>(-63129i, i32(-2147483648), global3.x, 1i), firstLeadingBit(vec4<i32>(u_input.b.x, u_input.a, -20958i, -40534i))), ~(vec4<i32>(global3.x, -82419i, global3.x, -7500i) << (vec4<u32>(1u, 4294967295u, 55981u, 0u) % vec4<u32>(32u))))))) {
            case -1i: {
                let var_1 = func_7(Struct_2(var_0.b.zz, Struct_1(var_0.a, var_0.b, _wgslsmith_f_op_vec2_f32(-func_4(var_0.c).c), 2412f)), min(vec2<u32>(max(_wgslsmith_add_u32(30639u, 66592u), 0u), 30956u), vec2<u32>(1u, 1u))).b;
                var_0 = func_4(_wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(min(_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(floor(vec2<f32>(-631f, -786f))) + _wgslsmith_f_op_vec2_f32(-var_0.c)), vec2<f32>(_wgslsmith_f_op_f32(var_0.c.x - var_0.d), arg_0.x)))));
                let var_2 = Struct_4(_wgslsmith_f_op_f32(exp2(_wgslsmith_f_op_f32(-var_0.d))), ~((global3.x & countOneBits(u_input.b.x)) >> (~16516u % 32u)));
                global0 = array<Struct_5, 23>();
                global3 = ~select(~(-vec3<i32>(u_input.b.x, -17347i, i32(-2147483648))) >> (abs(_wgslsmith_mod_vec3_u32(vec3<u32>(11686u, 8502u, 4294967295u), vec3<u32>(21247u, 84203u, 33382u))) % vec3<u32>(32u)), _wgslsmith_div_vec3_i32(vec3<i32>(0i, global3.x, 2147483647i), vec3<i32>(u_input.b.x, var_2.b, var_2.b) | vec3<i32>(1i, 1i, 2290i)) >> (_wgslsmith_sub_vec3_u32(select(vec3<u32>(0u, 0u, 61443u), vec3<u32>(34404u, 0u, 0u), var_0.b.wzx), vec3<u32>(65058u, 124404u, 0u)) % vec3<u32>(32u)), true);
            }
            case 1i: {
            }
            case -21891i: {
                continue;
            }
            case 53833i: {
                var var_1 = vec3<i32>((_wgslsmith_mod_i32(global3.x, 17652i ^ u_input.a) << (~countOneBits(1u) % 32u)) | _wgslsmith_add_i32(global3.x, -15925i), -global3.x, reverseBits(global3.x));
                let var_2 = func_5(_wgslsmith_clamp_vec3_u32(countOneBits(firstTrailingBit(vec3<u32>(1u, 1u, 1u))), vec3<u32>(~_wgslsmith_div_u32(56653u, 43701u), ~_wgslsmith_dot_vec2_u32(vec2<u32>(8218u, 9097u), vec2<u32>(4093u, 38062u)), ~1u), vec3<u32>(select(1u, 1u, true), select(~6645u, _wgslsmith_mult_u32(0u, 5973u), var_0.b.x), 51797u)));
                global1 = array<f32, 12>();
            }
            default: {
                let var_1 = Struct_1(var_0.b.x, vec4<bool>(var_0.b.x, all(select(!vec4<bool>(var_0.b.x, true, var_0.a, true), select(var_0.b, vec4<bool>(false, true, false, false), var_0.b), any(global2[_wgslsmith_index_u32(4294967295u, 12u)]))), true, var_0.a || !(!var_0.a)), func_4(vec2<f32>(_wgslsmith_div_f32(var_0.c.x, -1826f), _wgslsmith_f_op_f32(-_wgslsmith_div_f32(var_0.d, global1[_wgslsmith_index_u32(38636u, 12u)])))).c, 315f);
                var var_2 = 20262i;
                var var_3 = false;
            }
        }
        for (var var_1 = global3.x; ; var_1 -= 1i) {
            if (LOOP_COUNTERS[8u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[8u] = LOOP_COUNTERS[8u] + 1u;
            var var_2 = func_4(vec2<f32>(1907f, _wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(reverseBits(min(1u, 4302u)), 12u)] - _wgslsmith_f_op_f32(var_0.d + -422f))));
            continue;
        }
    }
    let var_0 = Struct_2(func_3(func_5(min(vec3<u32>(1u, 1u, 1u), ~vec3<u32>(0u, 4294967295u, 1u))).a, vec4<bool>(true, true, true, true), true, Struct_1(all(func_7(Struct_2(vec2<bool>(true, false), Struct_1(true, vec4<bool>(true, false, false, false), arg_0, -625f)), vec2<u32>(11961u, 19142u)).b.wy), vec4<bool>(true, true, true, true), arg_0, _wgslsmith_f_op_f32(-func_7(Struct_2(vec2<bool>(true, true), Struct_1(true, vec4<bool>(true, false, false, false), vec2<f32>(2034f, -133f), 1000f)), vec2<u32>(92358u, 72615u)).c.x))).zy, Struct_1(_wgslsmith_f_op_f32(655f - _wgslsmith_f_op_f32(trunc(126f))) == global1[_wgslsmith_index_u32(53239u, 12u)], vec4<bool>(true, true, true, true), _wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(-vec2<f32>(-2469f, arg_0.x)) + _wgslsmith_f_op_vec2_f32(-vec2<f32>(global1[_wgslsmith_index_u32(0u, 12u)], arg_0.x)))), _wgslsmith_f_op_f32(floor(global1[_wgslsmith_index_u32(1u, 12u)]))));
    global0 = array<Struct_5, 23>();
    let var_1 = 1082f;
    return _wgslsmith_f_op_f32(floor(_wgslsmith_div_f32(736f, _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(_wgslsmith_f_op_f32(var_0.b.d - var_1) - 124f)))));
}

fn func_1(arg_0: Struct_2) -> Struct_3 {
    let var_0 = Struct_4(_wgslsmith_f_op_f32(func_2(vec2<f32>(-1000f, 648f))), 2147483647i);
    if (all(vec4<bool>(arg_0.b.a, func_3(1f, arg_0.b.b, any(!arg_0.b.b), arg_0.b).x, true, true))) {
        let var_1 = _wgslsmith_mod_i32(_wgslsmith_dot_vec2_i32(u_input.b, -(u_input.b >> (vec2<u32>(0u, 4294967295u) % vec2<u32>(32u)))), var_0.b) & -1i;
        var var_2 = _wgslsmith_f_op_vec4_f32(_wgslsmith_f_op_vec4_f32(-_wgslsmith_f_op_vec4_f32(max(_wgslsmith_f_op_vec4_f32(-vec4<f32>(global1[_wgslsmith_index_u32(10912u, 12u)], 180f, var_0.a, -330f)), vec4<f32>(-1107f, _wgslsmith_f_op_f32(func_2(vec2<f32>(-454f, 678f))), _wgslsmith_f_op_f32(f32(-1f) * -889f), _wgslsmith_f_op_f32(sign(global1[_wgslsmith_index_u32(4294967295u, 12u)])))))) - vec4<f32>(663f, _wgslsmith_f_op_f32(f32(-1f) * -120f), _wgslsmith_f_op_f32(var_0.a + _wgslsmith_f_op_f32(f32(-1f) * -658f)), _wgslsmith_f_op_f32(-arg_0.b.d)));
        global4 = array<vec3<f32>, 26>();
        var_2 = _wgslsmith_f_op_vec4_f32(-_wgslsmith_f_op_vec4_f32(select(_wgslsmith_f_op_vec4_f32(-_wgslsmith_f_op_vec4_f32(-vec4<f32>(arg_0.b.c.x, -563f, arg_0.b.c.x, global1[_wgslsmith_index_u32(4294967295u, 12u)]))), vec4<f32>(_wgslsmith_f_op_f32(325f - func_4(vec2<f32>(670f, var_2.x)).c.x), _wgslsmith_div_f32(global1[_wgslsmith_index_u32(~6869u, 12u)], _wgslsmith_f_op_f32(f32(-1f) * -164f)), var_2.x, var_0.a), vec4<bool>(arg_0.b.a, arg_0.b.b.x, arg_0.b.b.x, true))));
        global3 = vec3<i32>(u_input.a, reverseBits(u_input.a), 1i);
    }
    switch (-14545i) {
        case i32(-2147483648): {
            for (var var_1 = 0i; (var_1 < 2147483647i); var_1 += 1i) {
                if (LOOP_COUNTERS[9u] >= 1u) {
                    break;
                }
                (LOOP_COUNTERS)[9u] = LOOP_COUNTERS[9u] + 1u;
                let var_2 = func_6();
                var_1 = _wgslsmith_dot_vec3_i32(countOneBits(~(vec3<i32>(-4455i, global3.x, global3.x) & _wgslsmith_mult_vec3_i32(vec3<i32>(u_input.b.x, u_input.a, -4776i), vec3<i32>(154i, -1i, i32(-2147483648))))), _wgslsmith_sub_vec3_i32(firstLeadingBit(vec3<i32>(_wgslsmith_dot_vec3_i32(vec3<i32>(u_input.b.x, u_input.b.x, 7245i), vec3<i32>(u_input.b.x, global3.x, i32(-2147483648))), _wgslsmith_sub_i32(-1i, -15428i), u_input.b.x)), vec3<i32>(-var_0.b, max(var_0.b, global3.x), ~u_input.b.x) ^ ~(~vec3<i32>(0i, global3.x, 0i))));
            }
            let var_1 = arg_0.b.b;
            let var_2 = func_5(vec3<u32>(_wgslsmith_dot_vec2_u32(_wgslsmith_mult_vec2_u32(vec2<u32>(80147u, 4294967295u), vec2<u32>(4294967295u, 1u)), vec2<u32>(1u, 1u)), ~firstLeadingBit(4294967295u), _wgslsmith_sub_u32(1u, ~1u)) | vec3<u32>(_wgslsmith_mult_u32(1u, ~0u), 83607u, ~(~0u)));
            var var_3 = 1i;
        }
        case 0i: {
        }
        default: {
            loop {
                if (LOOP_COUNTERS[10u] >= 1u) {
                    break;
                }
                (LOOP_COUNTERS)[10u] = LOOP_COUNTERS[10u] + 1u;
                break;
            }
            let var_1 = _wgslsmith_f_op_vec2_f32(select(_wgslsmith_f_op_vec2_f32(-vec2<f32>(_wgslsmith_f_op_f32(f32(-1f) * -1559f), arg_0.b.d)), arg_0.b.c, arg_0.b.b.wz));
            var var_2 = arg_0.b.c;
        }
    }
    loop {
        if (LOOP_COUNTERS[11u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[11u] = LOOP_COUNTERS[11u] + 1u;
        switch (1i) {
            case -24310i: {
                global1 = array<f32, 12>();
                var var_1 = arg_0.b.a;
                var var_2 = ~0u;
                global3 = _wgslsmith_mult_vec3_i32(vec3<i32>(abs(-1i), 24023i, var_0.b), vec3<i32>(var_0.b, u_input.a, u_input.b.x));
                return Struct_3(Struct_1(false, !select(arg_0.b.b, !arg_0.b.b, arg_0.b.b), arg_0.b.c, _wgslsmith_f_op_f32(f32(-1f) * -1539f)), min(min(abs(vec3<u32>(94235u, 348u, 0u)), firstTrailingBit(vec3<u32>(44395u, 93989u, 4294967295u))), abs(_wgslsmith_add_vec3_u32(vec3<u32>(30024u, 1u, 4294967295u), vec3<u32>(16791u, 0u, 4294967295u)))) << (firstTrailingBit(~vec3<u32>(1u, 93933u, 0u)) % vec3<u32>(32u)));
            }
            case -13494i: {
                let var_1 = Struct_3(func_7(Struct_2(!vec2<bool>(arg_0.b.a, arg_0.a.x), Struct_1(!arg_0.a.x, func_7(arg_0, vec2<u32>(4294967295u, 4294967295u)).b, _wgslsmith_f_op_vec2_f32(-vec2<f32>(976f, -1666f)), _wgslsmith_div_f32(var_0.a, arg_0.b.d))), ~vec2<u32>(23979u, ~78655u)), select(vec3<u32>(~(~17216u), 2245u, ~(~96009u)), abs(countOneBits(vec3<u32>(1u, 1u, 1u))), func_4(_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(vec2<f32>(344f, -1000f) + arg_0.b.c) - func_4(arg_0.b.c).c)).a));
                global1 = array<f32, 12>();
                var var_2 = Struct_5(vec2<f32>(_wgslsmith_f_op_f32(ceil(_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(ceil(-314f))))), func_7(Struct_2(func_7(arg_0, vec2<u32>(4294967295u, var_1.b.x)).b.ww, Struct_1(var_1.a.b.x, var_1.a.b, arg_0.b.c, -492f)), ~_wgslsmith_sub_vec2_u32(var_1.b.zy, vec2<u32>(var_1.b.x, var_1.b.x))).d), vec3<i32>((_wgslsmith_dot_vec2_i32(vec2<i32>(16613i, global3.x), vec2<i32>(-23185i, var_0.b)) | ~u_input.a) ^ _wgslsmith_mod_i32(i32(-2147483648) << (var_1.b.x % 32u), global3.x), 407i, _wgslsmith_mod_i32(-(~global3.x), ~4484i)));
            }
            case -19445i: {
                let var_1 = Struct_5(func_7(arg_0, ~_wgslsmith_div_vec2_u32(max(vec2<u32>(1u, 4294967295u), vec2<u32>(4294967295u, 1u)), vec2<u32>(4294967295u, 28616u))).c, -vec3<i32>(_wgslsmith_mult_i32(_wgslsmith_clamp_i32(12431i, var_0.b, -72319i), 6532i), 6596i, 13545i));
                let var_2 = var_1;
                var var_3 = _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-arg_0.b.c.x));
                let var_4 = func_5(~max(abs(~vec3<u32>(36798u, 43579u, 9280u)), ~min(vec3<u32>(4294967295u, 4294967295u, 0u), vec3<u32>(17880u, 1u, 0u))));
                let var_5 = _wgslsmith_add_i32(0i, firstTrailingBit(-1i));
            }
            case -1i: {
                let var_1 = vec4<f32>(global1[_wgslsmith_index_u32(~(~(~_wgslsmith_add_u32(4294967295u, 64677u))), 12u)], func_5(vec3<u32>(select(~73624u, select(0u, 72449u, true), all(arg_0.b.b.yw)), ~(~43453u), _wgslsmith_dot_vec3_u32(vec3<u32>(4294967295u, 8181u, 1u), vec3<u32>(1u, 1u, 1u)))).a, _wgslsmith_f_op_f32(var_0.a + _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(f32(-1f) * -1313f))), var_0.a);
                return Struct_3(arg_0.b, vec3<u32>(1u, 1u, 1u));
            }
            default: {
                global3 = -vec3<i32>(2147483647i, var_0.b, ~((global3.x ^ 29355i) ^ countOneBits(var_0.b)));
                var var_1 = _wgslsmith_f_op_vec3_f32(-_wgslsmith_f_op_vec3_f32(-global4[_wgslsmith_index_u32(0u, 26u)]));
                let var_2 = _wgslsmith_f_op_f32(ceil(-661f));
                break;
            }
        }
        var var_1 = countOneBits(min(vec3<u32>(1u, 1u, 1u), vec3<u32>(_wgslsmith_mult_u32(countOneBits(0u), 1u), 1u, _wgslsmith_mod_u32(0u, 1u))));
        var var_2 = var_0;
        break;
    }
    let var_1 = -global3.x;
    return Struct_3(Struct_1(arg_0.a.x, func_7(Struct_2(func_7(arg_0, vec2<u32>(1u, 31924u)).b.yw, func_4(vec2<f32>(arg_0.b.d, -1226f))), _wgslsmith_add_vec2_u32(vec2<u32>(1u, 1u), select(vec2<u32>(1u, 14270u), vec2<u32>(4294967295u, 4294967295u), arg_0.b.b.zx))).b, arg_0.b.c, _wgslsmith_f_op_f32(select(_wgslsmith_f_op_f32(round(-1134f)), global1[_wgslsmith_index_u32(firstTrailingBit(43617u), 12u)], arg_0.b.b.x))), min(select(_wgslsmith_sub_vec3_u32(abs(vec3<u32>(10789u, 0u, 21632u)), _wgslsmith_sub_vec3_u32(vec3<u32>(1u, 0u, 27723u), vec3<u32>(4294967295u, 25423u, 4294967295u))), min(vec3<u32>(33110u, 1u, 0u), vec3<u32>(1u, 1u, 1u)), !vec3<bool>(false, arg_0.a.x, arg_0.a.x)), ~_wgslsmith_div_vec3_u32(firstTrailingBit(vec3<u32>(44149u, 1u, 1u)), ~vec3<u32>(1u, 0u, 53647u))));
}

fn func_8(arg_0: Struct_3, arg_1: Struct_1) -> f32 {
    loop {
        if (LOOP_COUNTERS[12u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[12u] = LOOP_COUNTERS[12u] + 1u;
        switch (firstTrailingBit(global3.x)) {
            case 27117i: {
            }
            case 0i: {
                let var_0 = Struct_3(Struct_1(any(arg_0.a.b), arg_0.a.b, _wgslsmith_f_op_vec2_f32(-vec2<f32>(_wgslsmith_f_op_f32(max(arg_1.d, global1[_wgslsmith_index_u32(1u, 12u)])), global1[_wgslsmith_index_u32(16065u, 12u)])), arg_0.a.c.x), _wgslsmith_mod_vec3_u32(vec3<u32>(1u ^ (arg_0.b.x >> (4972u % 32u)), ~firstLeadingBit(95284u), _wgslsmith_add_u32(max(arg_0.b.x, 4294967295u), 25692u | arg_0.b.x)), _wgslsmith_mod_vec3_u32(arg_0.b, abs(min(vec3<u32>(arg_0.b.x, arg_0.b.x, arg_0.b.x), vec3<u32>(arg_0.b.x, 1u, 12797u))))));
                var var_1 = Struct_2(!(!(!func_3(global1[_wgslsmith_index_u32(var_0.b.x, 12u)], arg_1.b, true, Struct_1(arg_1.b.x, vec4<bool>(var_0.a.a, false, var_0.a.a, arg_1.b.x), var_0.a.c, -2287f)).zz)), func_4(_wgslsmith_f_op_vec2_f32(_wgslsmith_div_vec2_f32(_wgslsmith_f_op_vec2_f32(round(var_0.a.c)), func_4(vec2<f32>(var_0.a.d, arg_1.d)).c) + _wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(arg_1.c + arg_0.a.c)))));
                global0 = array<Struct_5, 23>();
                let var_2 = _wgslsmith_f_op_vec4_f32(_wgslsmith_f_op_vec4_f32(vec4<f32>(-2512f, _wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(-var_0.a.c.x)), _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(var_0.b.x, 12u)] - arg_0.a.d) + _wgslsmith_f_op_f32(f32(-1f) * -678f)), _wgslsmith_f_op_f32(min(_wgslsmith_div_f32(var_0.a.d, 1939f), _wgslsmith_f_op_f32(select(var_1.b.d, -368f, true))))) * _wgslsmith_f_op_vec4_f32(exp2(_wgslsmith_f_op_vec4_f32(_wgslsmith_f_op_vec4_f32(-vec4<f32>(arg_0.a.d, -1139f, -1000f, global1[_wgslsmith_index_u32(arg_0.b.x, 12u)])) + vec4<f32>(501f, -439f, arg_0.a.d, -671f))))) + vec4<f32>(_wgslsmith_f_op_f32(-arg_1.c.x), _wgslsmith_f_op_f32(-1498f + _wgslsmith_f_op_f32(func_5(vec3<u32>(arg_0.b.x, var_0.b.x, var_0.b.x)).a - arg_0.a.d)), _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(trunc(_wgslsmith_f_op_f32(-var_0.a.c.x))) - -737f), func_1(Struct_2(func_1(Struct_2(vec2<bool>(false, true), arg_0.a)).a.b.zx, var_1.b)).a.d));
                var_1 = Struct_2(vec2<bool>(false, any(vec3<bool>(arg_0.a.a, all(arg_1.b), func_3(411f, vec4<bool>(true, false, true, false), true, var_1.b).x))), arg_0.a);
            }
            case -70410i: {
                continue;
            }
            case i32(-2147483648): {
                continue;
            }
            default: {
                let var_0 = arg_1;
                global1 = array<f32, 12>();
            }
        }
        global4 = array<vec3<f32>, 26>();
        var var_0 = !(_wgslsmith_f_op_f32(sign(arg_1.d)) >= _wgslsmith_f_op_f32(max(_wgslsmith_f_op_f32(arg_1.d * func_7(Struct_2(vec2<bool>(arg_1.b.x, arg_0.a.a), arg_0.a), vec2<u32>(114435u, 1u)).d), _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(-3596f + global1[_wgslsmith_index_u32(arg_0.b.x, 12u)]) + arg_1.c.x))));
    }
    for (var var_0 = -1i; (func_4(_wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(select(arg_0.a.c, vec2<f32>(global1[_wgslsmith_index_u32(arg_0.b.x, 12u)], -294f), vec2<bool>(arg_0.a.a, arg_0.a.a))))).a && ((1u | ~arg_0.b.x) == arg_0.b.x)) || (firstTrailingBit(_wgslsmith_dot_vec3_i32(vec3<i32>(i32(-2147483648), global3.x, global3.x), vec3<i32>(41896i, 2147483647i, i32(-2147483648)) & vec3<i32>(u_input.b.x, u_input.b.x, u_input.a))) <= firstTrailingBit(2147483647i)); var_0 += 1i) {
        if (LOOP_COUNTERS[13u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[13u] = LOOP_COUNTERS[13u] + 1u;
        loop {
            if (LOOP_COUNTERS[14u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[14u] = LOOP_COUNTERS[14u] + 1u;
            global4 = array<vec3<f32>, 26>();
            var var_1 = func_5(vec3<u32>(~_wgslsmith_mult_u32(arg_0.b.x, _wgslsmith_mult_u32(91424u, 4294967295u)), arg_0.b.x ^ countOneBits(abs(arg_0.b.x)), ~34088u));
            var var_2 = _wgslsmith_f_op_f32(898f - func_1(Struct_2(!select(vec2<bool>(true, false), arg_1.b.xx, vec2<bool>(arg_0.a.b.x, false)), func_4(_wgslsmith_f_op_vec2_f32(abs(arg_0.a.c))))).a.d);
            let var_3 = ~vec4<u32>(_wgslsmith_mod_u32(arg_0.b.x, _wgslsmith_mod_u32(_wgslsmith_sub_u32(6141u, arg_0.b.x), _wgslsmith_add_u32(arg_0.b.x, 43364u))), 1u, arg_0.b.x, ~arg_0.b.x);
            return _wgslsmith_f_op_f32(func_2(_wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(_wgslsmith_f_op_vec2_f32(-vec2<f32>(-1582f, -503f)) + vec2<f32>(_wgslsmith_f_op_f32(floor(-1325f)), -1147f)))));
        }
        let var_1 = _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(abs(arg_0.a.c.x)) - -1629f);
        for (var var_2 = 81i; var_2 != 19940i; var_2 += 1i) {
            if (LOOP_COUNTERS[15u] >= 1u) {
                break;
            }
            (LOOP_COUNTERS)[15u] = LOOP_COUNTERS[15u] + 1u;
            let var_3 = func_1(Struct_2(!select(arg_0.a.b.ww, !vec2<bool>(arg_0.a.a, true), !arg_0.a.a), func_1(Struct_2(vec2<bool>(arg_1.a, arg_1.a), arg_0.a)).a)).a;
        }
    }
    var var_0 = func_5(~max(~(~vec3<u32>(arg_0.b.x, arg_0.b.x, arg_0.b.x)), arg_0.b));
    if (arg_1.b.x && all(!(!(!vec3<bool>(false, arg_1.b.x, arg_1.a))))) {
        let var_1 = true;
        return arg_1.c.x;
    }
    var var_1 = global3.x;
    return -1106f;
}

@compute
@workgroup_size(1)
fn main() {
    var var_0 = vec3<bool>(false, true, true);
    for (var var_1 = 1i; var_1 == 2147483647i; var_1 -= 1i) {
        if (LOOP_COUNTERS[16u] >= 1u) {
            break;
        }
        (LOOP_COUNTERS)[16u] = LOOP_COUNTERS[16u] + 1u;
        continue;
    }
    global0 = array<Struct_5, 23>();
    var var_1 = vec3<f32>(_wgslsmith_f_op_f32(-_wgslsmith_f_op_f32(ceil(_wgslsmith_f_op_f32(func_8(func_1(Struct_2(vec2<bool>(var_0.x, false), Struct_1(var_0.x, vec4<bool>(var_0.x, var_0.x, var_0.x, var_0.x), vec2<f32>(394f, -396f), global1[_wgslsmith_index_u32(13574u, 12u)]))), Struct_1(var_0.x, vec4<bool>(false, true, var_0.x, false), vec2<f32>(1305f, global1[_wgslsmith_index_u32(95798u, 12u)]), global1[_wgslsmith_index_u32(1u, 12u)])))))), _wgslsmith_f_op_f32(_wgslsmith_f_op_f32(func_2(_wgslsmith_f_op_vec2_f32(-_wgslsmith_f_op_vec2_f32(-vec2<f32>(1416f, global1[_wgslsmith_index_u32(28283u, 12u)]))))) + 313f), _wgslsmith_f_op_f32(-_wgslsmith_div_f32(_wgslsmith_div_f32(_wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(1u, 12u)] + 1000f), _wgslsmith_f_op_f32(-160f - global1[_wgslsmith_index_u32(3532u, 12u)])), _wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(4294967295u, 12u)] * _wgslsmith_f_op_f32(global1[_wgslsmith_index_u32(41046u, 12u)] - global1[_wgslsmith_index_u32(22554u, 12u)])))));
    let var_2 = _wgslsmith_f_op_f32(sign(_wgslsmith_f_op_f32(step(var_1.x, global1[_wgslsmith_index_u32(select(_wgslsmith_dot_vec4_u32(vec4<u32>(16867u, 1u, 4294967295u, 53913u), vec4<u32>(39675u, 1u, 1u, 45592u)) << (1u % 32u), ~(~1u), func_4(_wgslsmith_f_op_vec2_f32(var_1.yz * vec2<f32>(global1[_wgslsmith_index_u32(4294967295u, 12u)], -1298f))).b.x), 12u)]))));
    let x = u_input.a;
    s_output = StorageBuffer(max(select(_wgslsmith_dot_vec2_i32(global3.yy, u_input.b), _wgslsmith_clamp_i32(-45912i, global3.x, global3.x), any(vec2<bool>(false, true))), global3.x) >> (28840u % 32u));
}

