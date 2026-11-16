#version 450

layout(location = 0) out vec3 vertexColor;

const vec2 positions[3] = vec2[](
    vec2(0.0, -0.62),
    vec2(0.62, 0.50),
    vec2(-0.62, 0.50)
);

const vec3 colors[3] = vec3[](
    vec3(0.30, 0.82, 1.00),
    vec3(0.78, 0.42, 1.00),
    vec3(0.36, 1.00, 0.70)
);

void main() {
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    vertexColor = colors[gl_VertexIndex];
}
