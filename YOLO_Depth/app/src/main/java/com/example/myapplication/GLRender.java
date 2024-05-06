/**
 * Create By Shawn.xiao at 2023/05/01
 */
package com.example.myapplication;

import static android.opengl.GLES32.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES32.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES32.GL_COMPILE_STATUS;
import static android.opengl.GLES32.GL_FLOAT;
import static android.opengl.GLES32.GL_FRAGMENT_SHADER;
import static android.opengl.GLES32.GL_LINEAR;
import static android.opengl.GLES32.GL_LINK_STATUS;
import static android.opengl.GLES32.GL_TEXTURE0;
import static android.opengl.GLES32.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES32.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES32.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES32.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES32.GL_TRIANGLE_STRIP;
import static android.opengl.GLES32.GL_VERTEX_SHADER;
import static android.opengl.GLES32.glClearColor;
import static com.example.myapplication.MainActivity.Process_Init;
import static com.example.myapplication.MainActivity.Process_Texture;
import static com.example.myapplication.MainActivity.Run_Depth;
import static com.example.myapplication.MainActivity.Run_YOLO;
import static com.example.myapplication.MainActivity.class_result;
import static com.example.myapplication.MainActivity.currentFocusDistance;
import static com.example.myapplication.MainActivity.labels;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES32;
import android.opengl.GLSurfaceView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRender implements GLSurfaceView.Renderer {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(2);
    @SuppressLint("StaticFieldLeak")
    private static Context mContext;
    private static int mVertexLocation;
    private static int mTextureLocation;
    private static int mUTextureLocation;
    private static int mVMatrixLocation;
    private static int box_position;
    private static int box_color;
    private static int ShaderProgram_Camera;
    private static int ShaderProgram_YOLO;
    private static final int BYTES_FLOAT_2 = 8;
    public static final int camera_width = 1280;  // Please modify the project.h file simultaneously when editing these values.
    public static final int camera_height = 720;
    private static final int yolo_width = 512;
    private static final int yolo_height = 288;
    private static final int depth_width = 518;
    private static final int depth_height = 294;
    private static final int yolo_num_boxes = 3024;
    private static final int yolo_num_class = 80 + 4;  // 4 for axes(x, y, w, h)
    private static final int depth_pixels = depth_width * depth_height;
    private static final int depth_central_position = (depth_pixels - depth_width) / 2;
    public static final float depth_adjust_factor = 0.2f;  // Please adjust it by yourself to get more depth accuracy.
    private static final float yolo_detect_threshold = 0.4f;
    private static final float color_factor = 1.f / (1.f - yolo_detect_threshold);
    private static final float line_width = 7.f;  // draw boxes
    private static final float depth_w_factor = (float) depth_width / yolo_width;
    private static final float depth_h_factor = (float) depth_height / yolo_height;
    private static final float inv_yolo_width = 2.f / (float) yolo_width;
    private static final float inv_yolo_height = 2.f / (float) yolo_height;
    private static final float NMS_threshold_w = (float) yolo_width * 0.05f;
    private static final float NMS_threshold_h = (float) yolo_height * 0.05f;
    public static float FPS;
    public static float central_depth;
    private static final float[] mVertexCoord = {
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
    };
    private static final float[] mTextureCoord = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
    };
    private static final float[] lowColor = {1.0f, 1.0f, 0.0f}; // Yellow for low confidence.
    private static final float[] highColor = {1.0f, 0.0f, 0.0f}; // Red for high confidence.
    private static float[] image_rgb = new float[camera_width * camera_height];
    private static float[] depth_results = new float[depth_pixels];
    public static final float[] vMatrix = new float[16];
    private static final int[] mTextureId = new int[1];
    private static final int mVertexCoord_half_len = mVertexCoord.length / 2;
    private static final String VERTEX_ATTRIB_POSITION = "aPosVertex";
    private static final String VERTEX_ATTRIB_TEXTURE_POSITION = "aTexVertex";
    private static final String UNIFORM_TEXTURE = "camera_texture";
    private static final String UNIFORM_VMATRIX = "vMatrix";
    private static final String BOX_POSITION = "box_position";
    private static final String BOX_COLOR = "box_color";
    private static final String camera_vertex_shader_name = "camera_vertex_shader.glsl";
    private static final String camera_fragment_shader_name = "camera_fragment_shader.glsl";
    private static final String yolo_vertex_shader_name = "yolo_vertex_shader.glsl";
    private static final String yolo_fragment_shader_name = "yolo_fragment_shader.glsl";
    public static SurfaceTexture mSurfaceTexture;
    private static boolean run_yolo = true;  // true for turn on the function.
    private static boolean run_depth = true;  // true for turn on the function. Enabling both YOLO and depth estimation simultaneously decrease performance by 30+%.
    private static final List<ArrayList<Classifier.Recognition>> draw_queue_yolo = new ArrayList<>();
    public GLRender(Context context) {
        mContext = context;
    }
    public SurfaceTexture getSurfaceTexture() {
        return mSurfaceTexture;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES32.glEnable(GLES32.GL_BLEND);
        GLES32.glBlendFunc(GLES32.GL_SRC_ALPHA, GLES32.GL_ONE_MINUS_SRC_ALPHA);
        glClearColor(0.f, 0.0f, 0.0f, 1.0f);
        ShaderProgram_Camera = createAndLinkProgram(camera_vertex_shader_name, camera_fragment_shader_name);
        ShaderProgram_YOLO = createAndLinkProgram(yolo_vertex_shader_name, yolo_fragment_shader_name);
        initTexture();
        initAttribLocation();
        Process_Init(mTextureId[0]);
        ((MainActivity) mContext).openCamera();
    }
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES32.glViewport(0, 0, camera_height, camera_width);
    }
    @Override
    public void onDrawFrame(GL10 gl) {
        mSurfaceTexture.updateTexImage();
        mSurfaceTexture.getTransformMatrix(vMatrix);
        Draw_Camera_Preview();
        if (!run_yolo && !run_depth) {
            image_rgb = Process_Texture();
        }
        if (run_yolo) {
            run_yolo = false;
            executorService.execute(() -> {
                long t = System.currentTimeMillis();
                draw_queue_yolo.add(Post_Process_Yolo(Run_YOLO(image_rgb)));
                FPS = 1000.f / (System.currentTimeMillis()-t);
                run_yolo = true;
            });
        }
        if (run_depth) {
            run_depth = false;
            executorService.execute(() -> {
                depth_results = Run_Depth(image_rgb);
                central_depth = depth_results[depth_central_position] * currentFocusDistance;
                run_depth = true;
            });
        }
        if (!draw_queue_yolo.isEmpty()) {
            drawBox(draw_queue_yolo.remove(0));
        }
    }
    private static ArrayList<Classifier.Recognition> Post_Process_Yolo(float[] outputs) {
        List<Classifier.Recognition> detections = new ArrayList<>(yolo_num_boxes);
        int startIndex = 0;
        for (int i = 0; i < yolo_num_boxes; ++i) {
            int class_id = 4;
            float maxScore = outputs[startIndex + 4];
            for (int j = startIndex + 5; j < startIndex + yolo_num_class; ++j) {
                float score = outputs[j];
                if (score > maxScore) {
                    maxScore = score;
                    class_id = j - startIndex;
                }
            }
            if (maxScore >= yolo_detect_threshold) {
                float delta_x = outputs[startIndex + 2] * 0.5f;
                float delta_y = outputs[startIndex + 3] * 0.5f;
                RectF rect = new RectF(
                        Math.max(0.f, outputs[startIndex] - delta_x),
                        Math.max(0.f, outputs[startIndex + 1] - delta_y),
                        Math.min(yolo_width, outputs[startIndex] + delta_x),
                        Math.min(yolo_height, outputs[startIndex + 1] + delta_y)
                );
                detections.add(new Classifier.Recognition("", labels.get(class_id - 4), maxScore, rect));
            }
            startIndex += yolo_num_class;
        }

        // NMS
        ArrayList<Classifier.Recognition> nmsList = new ArrayList<>(yolo_num_boxes);
        if (!detections.isEmpty()) {
            ArrayList<Classifier.Recognition> temp_list = new ArrayList<>(yolo_num_boxes);
            ArrayList<Classifier.Recognition> delete_list = new ArrayList<>(yolo_num_boxes);
            temp_list.add(detections.remove(0));
            int count = 1;
            for (Classifier.Recognition d : detections) {
                if (!Objects.equals(d.getTitle(), detections.get(count - 1).getTitle())) {
                    while (!temp_list.isEmpty()) {
                        Classifier.Recognition max_score = temp_list.remove(0);
                        for (Classifier.Recognition j : temp_list) {
                            if (same_item(max_score.getLocation(), j.getLocation())) {
                                if (j.getConfidence() > max_score.getConfidence()) {
                                    max_score = j;
                                }
                                delete_list.add(j);
                            }
                        }
                        nmsList.add(max_score);
                        temp_list.removeAll(delete_list);
                        delete_list.clear();
                    }
                }
                temp_list.add(d);
                count ++;
            }
            while (!temp_list.isEmpty()) {
                delete_list.clear();
                Classifier.Recognition max_score = temp_list.remove(0);
                for (Classifier.Recognition j : temp_list) {
                    if (same_item(max_score.getLocation(), j.getLocation())) {
                        if (j.getConfidence() > max_score.getConfidence()) {
                            max_score = j;
                        }
                        delete_list.add(j);
                    }
                }
                nmsList.add(max_score);
                temp_list.removeAll(delete_list);
            }
        }
        return nmsList;
    }
    private static boolean same_item(RectF a, RectF b) {
        if (Math.abs(a.right - b.right) <= NMS_threshold_w) {
            if (Math.abs(a.top - b.top) <= NMS_threshold_h) {
                if (Math.abs(a.left - b.left) <= NMS_threshold_w) {
                    return Math.abs(a.bottom - b.bottom) <= NMS_threshold_h;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
    }
    private void drawBox(ArrayList<Classifier.Recognition> nmsList) {
        GLES32.glUseProgram(ShaderProgram_YOLO);
        for (int i = 0; i < nmsList.size(); i++) {
            Classifier.Recognition draw_result = nmsList.get(i);
            RectF box = draw_result.getLocation();
            int target_position = (int) (((box.top + box.bottom) * 0.5f - 1.f) * depth_h_factor) * depth_width + (int) ((box.left + box.right) * 0.25f * depth_w_factor);
            if (target_position >= depth_pixels) {
                target_position = depth_pixels - 1;
            }
            class_result.append(i).append(". ").append(draw_result.getTitle()).append(" / ").append(String.format("%.2f", 100.f * draw_result.getConfidence())).append("% / ").append(String.format("%.2f", currentFocusDistance * depth_results[target_position])).append("m").append("\n");
            box.top = -(box.top * inv_yolo_height - 1.f);
            box.bottom =  -(box.bottom * inv_yolo_height - 1.f);
            box.left = -(box.left * inv_yolo_width - 1.f);
            box.right = -(box.right * inv_yolo_width - 1.f);
            float[] rotatedVertices = {
                    box.top, box.left,
                    box.top, box.right,
                    box.bottom, box.right,
                    box.bottom, box.left
            };
            float[] color = getColorFromConfidence(draw_result.getConfidence());
            GLES32.glUniform4f(box_color, color[0], color[1], color[2], 1.f);
            GLES32.glVertexAttribPointer(box_position, 2, GLES32.GL_FLOAT, false, BYTES_FLOAT_2, getFloatBuffer(rotatedVertices));
            GLES32.glDrawArrays(GLES32.GL_LINE_LOOP, 0, 4);
        }
    }
    private static void Draw_Camera_Preview() {
        GLES32.glClear(GL_COLOR_BUFFER_BIT);
        GLES32.glUseProgram(ShaderProgram_Camera);
        GLES32.glVertexAttribPointer(mVertexLocation, 2, GL_FLOAT, false, 0, getFloatBuffer(mVertexCoord));
        GLES32.glVertexAttribPointer(mTextureLocation, 2, GL_FLOAT, false, 0, getFloatBuffer(mTextureCoord));
        GLES32.glEnableVertexAttribArray(mVertexLocation);
        GLES32.glEnableVertexAttribArray(mTextureLocation);
        GLES32.glUniformMatrix4fv(mVMatrixLocation, 1, false, vMatrix, 0);
        GLES32.glDrawArrays(GL_TRIANGLE_STRIP, 0, mVertexCoord_half_len);
    }
    private static void initAttribLocation() {
        GLES32.glLineWidth(line_width);
        mVertexLocation = GLES32.glGetAttribLocation(ShaderProgram_Camera, VERTEX_ATTRIB_POSITION);
        mTextureLocation = GLES32.glGetAttribLocation(ShaderProgram_Camera, VERTEX_ATTRIB_TEXTURE_POSITION);
        mUTextureLocation = GLES32.glGetUniformLocation(ShaderProgram_Camera, UNIFORM_TEXTURE);
        mVMatrixLocation = GLES32.glGetUniformLocation(ShaderProgram_Camera, UNIFORM_VMATRIX);
        box_position = GLES32.glGetAttribLocation(ShaderProgram_YOLO, BOX_POSITION);
        box_color = GLES32.glGetUniformLocation(ShaderProgram_YOLO, BOX_COLOR);
    }
    private static void initTexture() {
        GLES32.glGenTextures(mTextureId.length, mTextureId, 0);
        GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId[0]);
        GLES32.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GLES32.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GLES32.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GLES32.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        mSurfaceTexture = new SurfaceTexture(mTextureId[0]);
        mSurfaceTexture.setDefaultBufferSize(camera_width, camera_height);
        GLES32.glActiveTexture(GL_TEXTURE0);
        GLES32.glUniform1i(mUTextureLocation, 0);
    }
    private static int createAndLinkProgram(String vertexShaderFN, String fragShaderFN) {
        int shaderProgram = GLES32.glCreateProgram();
        if (shaderProgram == 0) {
            return 0;
        }
        AssetManager mgr = mContext.getResources().getAssets();
        int vertexShader = loadShader(GL_VERTEX_SHADER, loadShaderSource(mgr, vertexShaderFN));
        if (0 == vertexShader) {
            return 0;
        }
        int fragmentShader = loadShader(GL_FRAGMENT_SHADER, loadShaderSource(mgr, fragShaderFN));
        if (0 == fragmentShader) {
            return 0;
        }
        GLES32.glAttachShader(shaderProgram, vertexShader);
        GLES32.glAttachShader(shaderProgram, fragmentShader);
        GLES32.glLinkProgram(shaderProgram);
        int[] linked = new int[1];
        GLES32.glGetProgramiv(shaderProgram, GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            GLES32.glDeleteProgram(shaderProgram);
            return 0;
        }
        return shaderProgram;
    }
    private static int loadShader(int type, String shaderSource) {
        int shader = GLES32.glCreateShader(type);
        if (shader == 0) {
            return 0;
        }
        GLES32.glShaderSource(shader, shaderSource);
        GLES32.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES32.glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES32.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
    private static String loadShaderSource(AssetManager mgr, String file_name) {
        StringBuilder strBld = new StringBuilder();
        String nextLine;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(mgr.open(file_name)));
            while ((nextLine = br.readLine()) != null) {
                strBld.append(nextLine).append('\n');
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return strBld.toString();
    }
    private static FloatBuffer getFloatBuffer(float[] array) {
        FloatBuffer buffer = ByteBuffer.allocateDirect((array.length << 2)).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(array).position(0);
        return buffer;
    }
    private static float[] getColorFromConfidence(float confidence) {
        float[] color = new float[3];
        float factor = (confidence - yolo_detect_threshold) * color_factor;
        for (int i = 0; i < 3; ++i) {
            color[i] = lowColor[i] + (highColor[i] - lowColor[i]) * factor;
        }
        return color;
    }
}