package com.xpe.mobile.preview;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GLES2 ping-pong compositor. All methods run on one thread with a current EGL context. */
public final class BitmapShaderBaseline implements AutoCloseable {
    private static final String COPY = "#version 100\nprecision mediump float;\n"
            + "varying mediump vec2 uv; uniform sampler2D screenTexture;\n"
            + "void main(){ gl_FragColor=texture2D(screenTexture,uv); }";
    private static final Pattern UV_PRECISION = Pattern.compile("varying\\s+(?:(lowp|mediump|highp)\\s+)?vec2\\s+uv\\s*;");
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1});
    private final FloatBuffer uploadedQuad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0});
    private final Map<String, Program> programs = new HashMap<>();
    private final Set<ShaderTimeline.Effect> invalid = new HashSet<>();
    private final Consumer<String> errors;
    private final int[] textures = new int[3];
    private final int[] fbos = new int[2];
    private final float[] sampled = new float[4];
    private Program copy;
    private int width, height;
    private ShaderTimeline prepared;

    public BitmapShaderBaseline(Consumer<String> errors) {
        this.errors = errors;
        GLES20.glGenTextures(3, textures, 0);
        GLES20.glGenFramebuffers(2, fbos, 0);
        for (int texture : textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        copy = new Program(COPY);
    }

    /** Compile once before playback; switching packages releases every old program. */
    public void prepare(ShaderTimeline timeline) {
        if (prepared == timeline) return;
        for (Program program : programs.values()) if (program != null) program.close();
        programs.clear();
        invalid.clear();
        prepared = timeline;
        for (ShaderTimeline.Effect effect : timeline.effects) {
            if (!programs.containsKey(effect.source)) {
                try { programs.put(effect.source, new Program(effect.source)); }
                catch (RuntimeException ex) {
                    programs.put(effect.source, null);
                    errors.accept(effect.name + ": " + ex.getMessage());
                }
            }
            Program program = programs.get(effect.source);
            if (program != null) try {
                for (Map.Entry<String, float[]> item : effect.defaults.entrySet()) program.validate(item.getKey(), item.getValue().length);
                for (Map.Entry<String, ShaderTimeline.Animation> item : effect.uniforms.entrySet()) program.validate(item.getKey(), item.getValue().size);
                program.validate("time", 1); program.validate("screenSize", 2); program.validate("UVScale", 2);
            } catch (IllegalArgumentException ex) {
                invalid.add(effect); errors.accept(effect.name + ": " + ex.getMessage());
            }
        }
    }

    public void render(Bitmap gameplay, Bitmap overlay, ShaderTimeline timeline,
                       double seconds, RectF viewport, int outputWidth, int outputHeight) {
        prepare(timeline);
        resize(gameplay.getWidth(), gameplay.getHeight());
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        upload(gameplay);
        target(0);
        draw(copy, textures[2], true, 1, 1);
        int current = effects(timeline, seconds, viewport, false, 0);
        // Canvas bitmaps contain premultiplied alpha, including antialiased HUD/text edges.
        upload(overlay);
        target(current);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        draw(copy, textures[2], true, 1, 1);
        GLES20.glDisable(GLES20.GL_BLEND);
        current = effects(timeline, seconds, viewport, true, current);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        // Display is opaque, just like prpr's final screen blit. Some original presets write RGB only.
        GLES20.glColorMask(true, true, true, true);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glColorMask(true, true, true, false);
        draw(copy, textures[current], false, 1, 1);
        GLES20.glColorMask(true, true, true, true);
        check("render");
    }

    private int effects(ShaderTimeline timeline, double seconds, RectF viewport, boolean global, int current) {
        for (ShaderTimeline.Effect effect : timeline.effects) {
            if (effect.global != global || !effect.active(seconds) || invalid.contains(effect)) continue;
            Program program = programs.get(effect.source);
            if (program == null) continue;
            int next = 1 - current;
            target(next);
            // Preserve the surrounding background when a local effect writes only the chart viewport.
            draw(copy, textures[current], false, 1, 1);
            GLES20.glUseProgram(program.id);
            // prpr owns one material per effect. Shared programs must not leak unspecified vars.
            program.resetUniforms();
            for (Map.Entry<String, float[]> item : effect.defaults.entrySet()) {
                program.uniform(item.getKey(), item.getValue(), item.getValue().length);
            }
            for (Map.Entry<String, ShaderTimeline.Animation> item : effect.uniforms.entrySet()) {
                item.getValue().sample(seconds, sampled);
                program.uniform(item.getKey(), sampled, item.getValue().size);
            }
            program.uniform("time", new float[]{(float) seconds}, 1);
            program.uniform("screenSize", new float[]{width, height}, 2);
            if (!global) {
                int x = Math.round(viewport.left), y = Math.round(height - viewport.bottom);
                int w = Math.max(1, Math.round(viewport.width())), h = Math.max(1, Math.round(viewport.height()));
                GLES20.glViewport(x, y, w, h);
            }
            float sx = global ? 1 : viewport.width() / width;
            float sy = global ? 1 : viewport.height() / height;
            draw(program, textures[current], false, sx, sy);
            current = next;
        }
        return current;
    }

    private void target(int index) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[index]);
        GLES20.glViewport(0, 0, width, height);
    }

    private void upload(Bitmap bitmap) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2]);
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
    }

    private void resize(int w, int h) {
        if (width == w && height == h) return;
        int[] maximum = new int[1]; GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0);
        if (w <= 0 || h <= 0 || w > maximum[0] || h > maximum[0]) throw new IllegalArgumentException("Preview texture exceeds GPU limits");
        width = w; height = h;
        for (int i = 0; i < 3; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            if (i == 2) continue;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, textures[i], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete shader framebuffer");
            }
        }
        check("resize");
    }

    private void draw(Program program, int texture, boolean flip, float sx, float sy) {
        GLES20.glUseProgram(program.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(program.location("screenTexture"), 0);
        GLES20.glUniform2f(program.location("UVScale"), sx, sy);
        FloatBuffer vertices = flip ? uploadedQuad : quad;
        vertices.position(0);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, vertices);
        vertices.position(2);
        GLES20.glEnableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void check(String operation) {
        int code = GLES20.glGetError();
        if (code != GLES20.GL_NO_ERROR) throw new IllegalStateException(operation + ": GL 0x" + Integer.toHexString(code));
    }

    @Override public void close() {
        for (Program program : programs.values()) if (program != null) program.close();
        programs.clear(); copy.close();
        GLES20.glDeleteTextures(3, textures, 0); GLES20.glDeleteFramebuffers(2, fbos, 0);
    }

    private static final class Program implements AutoCloseable {
        final int id;
        final Map<String, Integer> locations = new HashMap<>();
        final Map<String, Integer> types = new HashMap<>();
        Program(String source) {
            Matcher precision = UV_PRECISION.matcher(source);
            String qualifier = precision.find() && precision.group(1) != null ? precision.group(1) : "mediump";
            String vertex = "#version 100\nattribute vec2 position; attribute vec2 texcoord;\n"
                    + "varying " + qualifier + " vec2 uv; uniform vec2 UVScale;\n"
                    + "void main(){ gl_Position=vec4(position,0.0,1.0);"
                    + "uv=(texcoord-vec2(0.5))*UVScale+vec2(0.5); }";
            int vs = compile(GLES20.GL_VERTEX_SHADER, vertex), fs = 0;
            int program = GLES20.glCreateProgram();
            try {
                fs = compile(GLES20.GL_FRAGMENT_SHADER, source);
                GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs);
                GLES20.glBindAttribLocation(program, 0, "position"); GLES20.glBindAttribLocation(program, 1, "texcoord");
                GLES20.glLinkProgram(program);
                int[] status = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
                if (status[0] == 0) throw new IllegalArgumentException(GLES20.glGetProgramInfoLog(program));
                id = program;
                GLES20.glGetProgramiv(id, GLES20.GL_ACTIVE_UNIFORMS, status, 0);
                int[] size = new int[1], type = new int[1];
                for (int i = 0; i < status[0]; i++) {
                    String name = GLES20.glGetActiveUniform(id, i, size, 0, type, 0);
                    types.put(name, type[0]);
                }
            } catch (RuntimeException ex) { GLES20.glDeleteProgram(program); throw ex; }
            finally { GLES20.glDeleteShader(vs); if (fs != 0) GLES20.glDeleteShader(fs); }
        }
        int location(String name) {
            Integer location = locations.get(name);
            if (location == null) { location = GLES20.glGetUniformLocation(id, name); locations.put(name, location); }
            return location;
        }
        void uniform(String name, float[] value, int size) {
            int location = location(name);
            if (location < 0) return;
            switch (size) {
                case 1: GLES20.glUniform1f(location, value[0]); break;
                case 2: GLES20.glUniform2f(location, value[0], value[1]); break;
                case 4: GLES20.glUniform4f(location, value[0], value[1], value[2], value[3]); break;
                default: throw new IllegalArgumentException("Unsupported uniform " + name);
            }
        }
        void validate(String name, int size) {
            Integer type = types.get(name);
            if (type == null) return; // Optimized-out or unused uniforms are valid in prpr.
            int expected = size == 1 ? GLES20.GL_FLOAT : size == 2 ? GLES20.GL_FLOAT_VEC2 : GLES20.GL_FLOAT_VEC4;
            if (type != expected) throw new IllegalArgumentException("Uniform type mismatch: " + name);
        }
        void resetUniforms() {
            for (Map.Entry<String, Integer> item : types.entrySet()) {
                int location = location(item.getKey());
                switch (item.getValue()) {
                    case GLES20.GL_FLOAT: GLES20.glUniform1f(location, 0); break;
                    case GLES20.GL_FLOAT_VEC2: GLES20.glUniform2f(location, 0, 0); break;
                    case GLES20.GL_FLOAT_VEC4: GLES20.glUniform4f(location, 0, 0, 0, 0); break;
                    default: break;
                }
            }
        }
        static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
            int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader);
                throw new IllegalArgumentException(log);
            }
            return shader;
        }
        @Override public void close() { GLES20.glDeleteProgram(id); }
    }
}
