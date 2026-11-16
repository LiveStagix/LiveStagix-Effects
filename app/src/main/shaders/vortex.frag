#version 450

layout(location = 0) out vec4 fragmentColor;

layout(push_constant) uniform FrameData {
    vec4 resolutionTime;
} frameData;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 local = fract(p);
    local = local * local * (3.0 - 2.0 * local);

    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));

    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    const mat2 transform = mat2(1.6, 1.2, -1.2, 1.6);

    for (int octave = 0; octave < 5; ++octave) {
        value += amplitude * valueNoise(p);
        p = transform * p + vec2(13.7, -9.2);
        amplitude *= 0.5;
    }

    return value;
}

void main() {
    vec2 resolution = max(frameData.resolutionTime.xy, vec2(1.0));
    float time = frameData.resolutionTime.z;

    vec2 uv = gl_FragCoord.xy / resolution;
    vec2 position = uv * 2.0 - 1.0;
    position.x *= resolution.x / resolution.y;

    float radius = length(position);
    float angle = atan(position.y, position.x);

    vec2 domain = vec2(
        angle * 1.35 + time * 0.16,
        radius * 3.2 - time * 0.24
    );

    float warpA = fbm(domain * 2.1);
    float warpB = fbm(domain * 3.6 + vec2(8.2, -3.4));
    float turbulence = fbm(domain * 2.5 + vec2(-time * 0.07, time * 0.05));

    float phase = angle * 3.0 + radius * 12.0 - time * 1.05
        + (warpA - 0.5) * 7.0
        + (warpB - 0.5) * 4.0;

    float wave = 0.5 + 0.5 * sin(phase);
    float filament = pow(max(0.0, 1.0 - abs(wave - 0.55) * 3.5), 5.0);

    float ringRadius = 0.52 + 0.13 * sin(angle * 2.0 + time * 0.42 + turbulence * 2.0);
    float ring = exp(-abs(radius - ringRadius) * 12.0);
    float innerGlow = exp(-radius * 2.4);

    vec3 deepBlue = vec3(0.025, 0.055, 0.22);
    vec3 violet = vec3(0.32, 0.075, 0.68);
    vec3 cyan = vec3(0.025, 0.68, 0.78);
    vec3 teal = vec3(0.04, 0.42, 0.34);

    vec3 aurora = mix(violet, cyan, smoothstep(0.18, 0.82, turbulence));
    aurora = mix(aurora, teal, smoothstep(0.55, 0.95, warpB) * 0.55);

    vec3 color = deepBlue
        + aurora * (0.16 + filament * 1.35 + ring * 0.7)
        + vec3(0.34, 0.12, 0.72) * innerGlow * 0.55;

    float vignette = 1.0 - smoothstep(0.55, 1.55, radius);
    color *= 0.38 + vignette * 0.82;
    color = 1.0 - exp(-color * 1.35);

    fragmentColor = vec4(color, 1.0);
}
