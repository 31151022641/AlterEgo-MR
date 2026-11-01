"""
3D Avatar Rendering Engine powered by ModernGL and PyGLM.

Renders an expressive 3D facial avatar into an offscreen Framebuffer (1280x720) at 60 FPS.
Applies real-time 6DoF head pose retargeting (yaw, pitch, roll) and drives ARKit facial
morph targets (jawOpen, smile, blink, brow) with Blinn-Phong lighting and dynamic eye gaze.
"""

import math
from typing import Dict, Optional, Tuple
import glm
import moderngl
import numpy as np
from PIL import Image, ImageDraw


class ModernGlAvatarRenderer:
    """
    Offscreen GPU 3D avatar renderer for Windows Virtual Webcam streaming.
    """

    def __init__(self, width: int = 1280, height: int = 720):
        self.width = width
        self.height = height

        # 1. Create Standalone ModernGL Context (Headless / Offscreen)
        try:
            self.ctx = moderngl.create_context(standalone=True)
        except Exception as e:
            print(f"[Renderer] Standalone context failed ({e}), attempting default context...")
            self.ctx = moderngl.create_context()

        self.ctx.enable(moderngl.DEPTH_TEST | moderngl.CULL_FACE)
        self.ctx.cull_face = "back"

        # 2. Setup Offscreen Framebuffer (FBO)
        self.color_texture = self.ctx.texture((self.width, self.height), 4)
        self.depth_renderbuffer = self.ctx.depth_renderbuffer((self.width, self.height))
        self.fbo = self.ctx.framebuffer(
            color_attachments=[self.color_texture],
            depth_attachment=self.depth_renderbuffer,
        )

        # 3. Compile Shaders
        self.prog = self.ctx.program(
            vertex_shader=VERTEX_SHADER_SRC,
            fragment_shader=FRAGMENT_SHADER_SRC,
        )

        # 4. Build Mesh Geometry with Morph Targets
        self._build_avatar_mesh()

        # 5. Generate Procedural Stylized Texture
        self._create_avatar_texture()

        # Camera projection
        aspect = self.width / self.height
        self.proj = glm.perspective(glm.radians(45.0), aspect, 0.1, 100.0)
        self.view = glm.lookAt(
            glm.vec3(0.0, 0.05, 1.25),  # Camera eye
            glm.vec3(0.0, 0.0, 0.0),    # Target
            glm.vec3(0.0, 1.0, 0.0),    # Up
        )

        print(f"[Renderer] ModernGL 3D Avatar Engine initialized ({self.width}x{self.height} @ 60 FPS)")

    def render(
        self,
        pitch: float = 0.0,
        yaw: float = 0.0,
        roll: float = 0.0,
        pos_x: float = 0.0,
        pos_y: float = 0.0,
        pos_z: float = 0.0,
        blendshapes: Optional[Dict[str, float]] = None,
        gaze_x: float = 0.5,
        gaze_y: float = 0.5,
    ) -> np.ndarray:
        """
        Renders a single frame and returns a (height, width, 4) uint8 RGBA numpy array.
        """
        if blendshapes is None:
            blendshapes = {}

        # Bind offscreen FBO and clear background (modern studio dark gradient)
        self.fbo.use()
        self.ctx.clear(0.08, 0.11, 0.16, 1.0, depth=1.0)

        # Compute Model Matrix from Quest 6DoF Pose
        model = glm.mat4(1.0)
        # Translation
        model = glm.translate(model, glm.vec3(pos_x * 0.4, pos_y * 0.4, pos_z * 0.3))
        # Rotation: Yaw (horizontal turn), Pitch (nod), Roll (head tilt)
        model = glm.rotate(model, glm.radians(-yaw), glm.vec3(0.0, 1.0, 0.0))
        model = glm.rotate(model, glm.radians(pitch), glm.vec3(1.0, 0.0, 0.0))
        model = glm.rotate(model, glm.radians(-roll), glm.vec3(0.0, 0.0, 1.0))

        # Model View Projection
        mvp = self.proj * self.view * model
        normal_matrix = glm.mat3(glm.transpose(glm.inverse(model)))

        # Pass Matrix Uniforms
        self.prog["u_mvp"].write(mvp)
        self.prog["u_model"].write(model)
        self.prog["u_normal_mat"].write(normal_matrix)

        # Lighting uniforms
        self.prog["u_light_pos"].value = (1.5, 2.0, 2.5)
        self.prog["u_view_pos"].value = (0.0, 0.05, 1.25)
        self.prog["u_base_color"].value = (0.92, 0.78, 0.68)  # Realistic skin tone

        # Pass Blendshape morph weights
        jaw_open = blendshapes.get("jawOpen", 0.0)
        smile_l = blendshapes.get("mouthSmileLeft", 0.0)
        smile_r = blendshapes.get("mouthSmileRight", 0.0)
        smile_avg = (smile_l + smile_r) * 0.5
        blink_l = blendshapes.get("eyeBlinkLeft", 0.0)
        blink_r = blendshapes.get("eyeBlinkRight", 0.0)
        brow_up = blendshapes.get("browInnerUp", 0.0)

        self.prog["u_jaw_open"].value = float(jaw_open)
        self.prog["u_smile"].value = float(smile_avg)
        self.prog["u_blink_l"].value = float(blink_l)
        self.prog["u_blink_r"].value = float(blink_r)
        self.prog["u_brow_up"].value = float(brow_up)

        # Eye gaze offset into shader
        eye_dx = (gaze_x - 0.5) * 0.05
        eye_dy = (0.5 - gaze_y) * 0.05
        self.prog["u_gaze_offset"].value = (eye_dx, eye_dy)

        # Bind Texture and Draw
        self.texture.use(location=0)
        self.prog["u_diffuse_tex"].value = 0
        self.prog["u_use_texture"].value = True

        self.vao.render(moderngl.TRIANGLES)

        # Read back RGBA raw bytes from GPU Framebuffer
        raw_bytes = self.fbo.read(components=4, dtype="f1")
        frame_rgba = np.frombuffer(raw_bytes, dtype=np.uint8).reshape((self.height, self.width, 4))
        # Flip vertically because OpenGL FBO origin is bottom-left, while Windows webcam is top-left
        return np.flipud(frame_rgba)

    def _build_avatar_mesh(self):
        """
        Builds a canonical 3D human head mesh with vertex morph deltas for blendshapes.
        Attributes: in_pos (3), in_norm (3), in_uv (2), in_d_jaw (3), in_d_smile (3),
                    in_d_blink_l (3), in_d_blink_r (3), in_d_brow (3)
        Total 23 floats per vertex.
        """
        # Canonical anatomical vertices
        # fmt: off
        raw_vertices = [
            # ID 0: Forehead Center
            (0.0, 0.28, 0.12,   0.0, 0.4, 0.9,   0.50, 0.92,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.04, 0.01),
            # ID 1: Forehead Left
            (-0.16, 0.26, 0.08, -0.4, 0.3, 0.8,  0.25, 0.90,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.03, 0.01),
            # ID 2: Forehead Right
            (0.16, 0.26, 0.08,   0.4, 0.3, 0.8,  0.75, 0.90,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.03, 0.01),

            # ID 3: Brow Center
            (0.0, 0.16, 0.19,   0.0, 0.2, 0.98,  0.50, 0.72,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.07, 0.02),
            # ID 4: Eyebrow Left
            (-0.11, 0.15, 0.16, -0.3, 0.2, 0.9,  0.28, 0.72,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.06, 0.02),
            # ID 5: Eyebrow Right
            (0.11, 0.15, 0.16,   0.3, 0.2, 0.9,  0.72, 0.72,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.06, 0.02),

            # ID 6: Eye Left Upper Eyelid
            (-0.11, 0.09, 0.15, -0.2, 0.1, 0.95, 0.28, 0.60,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0,-0.05, 0.0, 0.0, 0.0, 0.0,  0.0, 0.02, 0.00),
            # ID 7: Eye Left Lower Eyelid
            (-0.11, 0.04, 0.14, -0.2,-0.1, 0.95, 0.28, 0.50,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.01, 0.0, 0.0, 0.0, 0.0,  0.0, 0.00, 0.00),
            # ID 8: Eye Right Upper Eyelid
            (0.11, 0.09, 0.15,   0.2, 0.1, 0.95, 0.72, 0.60,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0,-0.05, 0.0, 0.0, 0.02, 0.00),
            # ID 9: Eye Right Lower Eyelid
            (0.11, 0.04, 0.14,   0.2,-0.1, 0.95, 0.72, 0.50,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.01, 0.0, 0.0, 0.00, 0.00),

            # ID 10: Nose Bridge
            (0.0, 0.06, 0.22,   0.0, 0.1, 0.99,  0.50, 0.54,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.01, 0.00),
            # ID 11: Nose Tip
            (0.0,-0.04, 0.26,   0.0, 0.0, 1.0,   0.50, 0.40,   0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,  0.0, 0.00, 0.00),

            # ID 12: Cheek Left
            (-0.20, 0.01, 0.09, -0.6, 0.0, 0.7,  0.12, 0.45,   0.0, 0.0, 0.0, -0.02, 0.03, 0.01, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 13: Cheek Right
            (0.20, 0.01, 0.09,   0.6, 0.0, 0.7,  0.88, 0.45,   0.0, 0.0, 0.0,  0.02, 0.03, 0.01, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),

            # ID 14: Upper Lip Center
            (0.0,-0.12, 0.21,   0.0, 0.1, 0.98,  0.50, 0.30,   0.0,-0.03, 0.01, 0.0, 0.02, 0.0,  0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 15: Mouth Corner Left
            (-0.11,-0.15, 0.16,-0.4, 0.0, 0.85,  0.28, 0.26,   0.0,-0.06, 0.0, -0.03, 0.05, 0.01, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 16: Mouth Corner Right
            (0.11,-0.15, 0.16,  0.4, 0.0, 0.85,  0.72, 0.26,   0.0,-0.06, 0.0,  0.03, 0.05, 0.01, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 17: Lower Lip Center
            (0.0,-0.17, 0.20,   0.0,-0.2, 0.95,  0.50, 0.22,   0.0,-0.12,-0.02, 0.0, 0.01, 0.0,  0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),

            # ID 18: Chin / Jaw Tip
            (0.0,-0.28, 0.16,   0.0,-0.4, 0.85,  0.50, 0.06,   0.0,-0.16,-0.03, 0.0, 0.01, 0.0,  0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 19: Jaw Angle Left
            (-0.18,-0.22, 0.04,-0.6,-0.3, 0.6,   0.15, 0.15,   0.0,-0.12,-0.02, 0.0, 0.00, 0.0,  0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 20: Jaw Angle Right
            (0.18,-0.22, 0.04,  0.6,-0.3, 0.6,   0.85, 0.15,   0.0,-0.12,-0.02, 0.0, 0.00, 0.0,  0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),

            # ID 21: Temple Left
            (-0.24, 0.16,-0.05,-0.8, 0.1, 0.4,   0.04, 0.75,   0.0, 0.0, 0.0,   0.0, 0.0, 0.0,   0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
            # ID 22: Temple Right
            (0.24, 0.16,-0.05,  0.8, 0.1, 0.4,   0.96, 0.75,   0.0, 0.0, 0.0,   0.0, 0.0, 0.0,   0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.00, 0.00),
        ]

        # Triangulation index list
        indices = [
            # Forehead to Brows
            0, 1, 4,   0, 4, 3,   0, 3, 5,   0, 5, 2,
            1, 21, 4,  2, 5, 22,

            # Brows to Eyes & Nose
            3, 4, 10,  3, 10, 5,
            4, 6, 10,  5, 10, 8,
            6, 7, 10,  8, 10, 9,
            4, 21, 12, 4, 12, 6,
            5, 8, 13,  5, 13, 22,

            # Nose to Cheeks & Upper Lip
            10, 7, 11, 10, 11, 9,
            7, 12, 11, 9, 11, 13,
            11, 12, 15, 11, 15, 14,
            11, 14, 16, 11, 16, 13,

            # Mouth & Oral Cavity
            14, 15, 17, 14, 17, 16,

            # Jaw & Chin
            15, 19, 17, 16, 17, 20,
            17, 19, 18, 17, 18, 20,
            12, 19, 15, 13, 16, 20,
            12, 21, 19, 13, 20, 22
        ]
        # fmt: on

        vbo_data = np.array(raw_vertices, dtype="f4").flatten()
        ibo_data = np.array(indices, dtype="i4")

        self.vbo = self.ctx.buffer(vbo_data.tobytes())
        self.ibo = self.ctx.buffer(ibo_data.tobytes())

        # Vertex layout format: 3f 3f 2f 3f 3f 3f 3f 3f = 23 floats per vertex (92 bytes stride)
        layout = "3f 3f 2f 3f 3f 3f 3f 3f"
        attributes = [
            "in_pos",
            "in_norm",
            "in_uv",
            "in_d_jaw",
            "in_d_smile",
            "in_d_blink_l",
            "in_d_blink_r",
            "in_d_brow_up",
        ]
        self.vao = self.ctx.vertex_array(
            self.prog,
            [(self.vbo, layout, *attributes)],
            index_buffer=self.ibo,
        )

    def _create_avatar_texture(self):
        """Creates a smooth stylized human face texture with eyes, lips, and contours."""
        size = 512
        img = Image.new("RGBA", (size, size), (235, 195, 170, 255))
        draw = ImageDraw.Draw(img)

        # Eye socket shadows
        draw.ellipse([100, 180, 210, 270], fill=(215, 170, 145, 255))
        draw.ellipse([302, 180, 412, 270], fill=(215, 170, 145, 255))

        # Eyeballs (sclera)
        draw.ellipse([115, 195, 195, 255], fill=(245, 245, 248, 255))
        draw.ellipse([317, 195, 397, 255], fill=(245, 245, 248, 255))

        # Stylized Irises & Pupils
        draw.ellipse([135, 205, 175, 245], fill=(35, 115, 185, 255))
        draw.ellipse([337, 205, 377, 245], fill=(35, 115, 185, 255))
        draw.ellipse([147, 217, 163, 233], fill=(15, 15, 20, 255))
        draw.ellipse([349, 217, 365, 233], fill=(15, 15, 20, 255))

        # Eyebrows
        draw.polygon([(100, 170), (160, 155), (210, 168), (160, 162)], fill=(75, 45, 30, 255))
        draw.polygon([(412, 170), (352, 155), (302, 168), (352, 162)], fill=(75, 45, 30, 255))

        # Soft Cheek blush
        draw.ellipse([60, 260, 180, 330], fill=(245, 165, 150, 140))
        draw.ellipse([332, 260, 452, 330], fill=(245, 165, 150, 140))

        # Lips
        draw.polygon([(190, 380), (256, 365), (322, 380), (256, 395)], fill=(195, 85, 80, 255))
        draw.polygon([(195, 380), (256, 385), (317, 380), (256, 412)], fill=(210, 95, 90, 255))

        tex_bytes = img.tobytes()
        self.texture = self.ctx.texture((size, size), 4, tex_bytes)
        self.texture.build_mipmaps()
        self.texture.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)

    def close(self):
        """Releases GPU buffers."""
        try:
            self.vbo.release()
            self.ibo.release()
            self.vao.release()
            self.texture.release()
            self.color_texture.release()
            self.depth_renderbuffer.release()
            self.fbo.release()
        except Exception:
            pass


VERTEX_SHADER_SRC = """
#version 330

uniform mat4 u_mvp;
uniform mat4 u_model;
uniform mat3 u_normal_mat;

uniform float u_jaw_open;
uniform float u_smile;
uniform float u_blink_l;
uniform float u_blink_r;
uniform float u_brow_up;
uniform vec2 u_gaze_offset;

in vec3 in_pos;
in vec3 in_norm;
in vec2 in_uv;
in vec3 in_d_jaw;
in vec3 in_d_smile;
in vec3 in_d_blink_l;
in vec3 in_d_blink_r;
in vec3 in_d_brow_up;

out vec3 v_normal;
out vec3 v_frag_pos;
out vec2 v_uv;

void main() {
    // Accumulate blendshape morph targets
    vec3 morphed = in_pos
        + in_d_jaw * u_jaw_open
        + in_d_smile * u_smile
        + in_d_blink_l * u_blink_l
        + in_d_blink_r * u_blink_r
        + in_d_brow_up * u_brow_up;

    v_frag_pos = vec3(u_model * vec4(morphed, 1.0));
    v_normal = normalize(u_normal_mat * in_norm);

    // Apply eye gaze shift dynamically to the eye texture coordinates
    vec2 uv = in_uv;
    if (in_uv.y > 0.45 && in_uv.y < 0.65) {
        uv += u_gaze_offset;
    }
    v_uv = uv;

    gl_Position = u_mvp * vec4(morphed, 1.0);
}
"""

FRAGMENT_SHADER_SRC = """
#version 330

in vec3 v_normal;
in vec3 v_frag_pos;
in vec2 v_uv;

uniform vec3 u_light_pos;
uniform vec3 u_view_pos;
uniform vec3 u_base_color;
uniform sampler2D u_diffuse_tex;
uniform bool u_use_texture;

out vec4 fragColor;

void main() {
    vec3 norm = normalize(v_normal);
    vec3 light_dir = normalize(u_light_pos - v_frag_pos);
    float diff = max(dot(norm, light_dir), 0.0);

    vec3 view_dir = normalize(u_view_pos - v_frag_pos);
    vec3 reflect_dir = reflect(-light_dir, norm);
    float spec = pow(max(dot(view_dir, reflect_dir), 0.0), 24.0);

    vec3 ambient = vec3(0.32, 0.35, 0.40);
    vec3 diffuse = diff * vec3(1.0, 0.96, 0.90);
    vec3 specular = spec * vec3(0.35, 0.35, 0.35);

    vec4 tex_col = u_use_texture ? texture(u_diffuse_tex, v_uv) : vec4(u_base_color, 1.0);
    vec3 col = (ambient + diffuse + specular) * tex_col.rgb;

    fragColor = vec4(col, 1.0);
}
"""
