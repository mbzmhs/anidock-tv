package com.mbzmhs.anidocktv.player

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 原生视频层容器:视频用 SurfaceView 直出(系统/电视直接上屏，可走硬件插帧 MEMC)，
 * 字幕用独立的 GLSurfaceView 消费 VLC 的字幕 SurfaceTexture，并在 shader 里把
 * VLC 输出的直通 alpha 预乘后再交给 SurfaceFlinger。
 *
 * 为什么不能直接用 VLC 的独立字幕 SurfaceView:VLC 的 native 字幕合成把 RGBA
 * 直通 alpha 写入字幕面，安卓合成器按预乘 alpha 处理 → ASS 半透明阴影/描边出现
 * 白边。此处的片元 shader 做 `rgb *= a`，与 TextureView 视频路径里 VLC GL 合成
 * 的 alpha 结果一致，同时视频仍留在原生视频层。
 */
class NativeVideoLayout(context: Context) :
    FrameLayout(context), IVLCVout.OnNewVideoLayoutListener {

    val videoView = SurfaceView(context)
    /** GL 字幕纹理没就绪时的兜底:退回 VLC 原生字幕 SurfaceView(会有白边,但至少能看)。 */
    val fallbackSubtitleView = SurfaceView(context).apply {
        setZOrderMediaOverlay(true)
        visibility = View.GONE
    }

    private val subtitleGraphView = SubtitleGlView(context) { st ->
        subtitleTextureRef = st
        subtitleTextureDeferred.complete(st)
    }
    private val subtitleTextureDeferred = CompletableDeferred<SurfaceTexture>()
    @Volatile private var subtitleTextureRef: SurfaceTexture? = null
    private var glPaused = true

    private var videoW = 0
    private var videoH = 0
    private var visibleW = 0
    private var visibleH = 0
    private var sarNum = 1
    private var sarDen = 1
    private var scaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT

    /** 原生窗口尺寸(帧大小,不是视频显示矩形);由控制器用来调 vout.setWindowSize。 */
    var onWindowSize: ((Int, Int) -> Unit)? = null

    init {
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        addView(videoView, LayoutParams(fill, fill))
        addView(fallbackSubtitleView, LayoutParams(fill, fill))
        addView(subtitleGraphView, LayoutParams(fill, fill))
    }

    /** 等 GL 线程把字幕 SurfaceTexture 建好(控制器在 attach 前等待,超时则用兜底字幕层)。 */
    suspend fun awaitSubtitleTexture(): SurfaceTexture = subtitleTextureDeferred.await()

    /** GL 字幕层不可用时切回 VLC 原生字幕 SurfaceView(白边回归,但功能可用)。 */
    fun useFallbackSubtitles() {
        fallbackSubtitleView.visibility = View.VISIBLE
        subtitleGraphView.visibility = View.GONE
    }

    /** 画面比例菜单变化时由控制器同步。 */
    fun setScaleType(type: MediaPlayer.ScaleType) {
        scaleType = type
        requestLayout()
    }

    fun windowWidth(): Int = if (width > 0) width else resources.displayMetrics.widthPixels
    fun windowHeight(): Int = if (height > 0) height else resources.displayMetrics.heightPixels

    override fun onNewVideoLayout(
        vlcVout: IVLCVout?,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int,
    ) {
        // native 回调线程不保证是主线程;状态与布局都回到主线程处理
        post {
            videoW = width
            videoH = height
            visibleW = visibleWidth
            visibleH = visibleHeight
            this.sarNum = if (sarNum > 0) sarNum else 1
            this.sarDen = if (sarDen > 0) sarDen else 1
            onWindowSize?.invoke(windowWidth(), windowHeight())
            requestLayout()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (glPaused) {
            glPaused = false
            subtitleGraphView.onResume()
        }
    }

    override fun onDetachedFromWindow() {
        pauseGl()
        super.onDetachedFromWindow()
    }

    /** 会话卸载时调用(必须在 player.detachViews 之后):停 GL 线程并释放字幕 SurfaceTexture。 */
    fun releaseSubtitleTexture() {
        pauseGl()
        subtitleTextureRef?.release()
        subtitleTextureRef = null
    }

    private fun pauseGl() {
        if (!glPaused) {
            glPaused = true
            subtitleGraphView.onPause()
        }
    }

    /** 视频显示矩形的宽高比(0=铺满父容器)。 */
    private fun targetAspect(): Float {
        val vw = if (visibleW > 0) visibleW else videoW
        val vh = if (visibleH > 0) visibleH else videoH
        if (vw <= 0 || vh <= 0) return 0f
        return when (scaleType) {
            MediaPlayer.ScaleType.SURFACE_FILL -> 0f
            MediaPlayer.ScaleType.SURFACE_16_9 -> 16f / 9f
            MediaPlayer.ScaleType.SURFACE_4_3 -> 4f / 3f
            else -> vw.toFloat() * sarNum / (vh.toFloat() * sarDen)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pw = (r - l).coerceAtLeast(1)
        val ph = (b - t).coerceAtLeast(1)
        var w = pw
        var h = ph
        val aspect = targetAspect()
        if (aspect > 0f) {
            if (pw.toFloat() / ph > aspect) {
                h = ph
                w = (ph * aspect).toInt()
            } else {
                w = pw
                h = (pw / aspect).toInt()
            }
        }
        // 原始尺寸:按视频像素尺寸居中,超出部分由父容器裁剪
        if (scaleType == MediaPlayer.ScaleType.SURFACE_ORIGINAL && videoW > 0 && videoH > 0) {
            w = (videoW.toLong() * sarNum / sarDen).toInt().coerceAtLeast(1)
            h = videoH.coerceAtLeast(1)
        }
        val left = (pw - w) / 2
        val top = (ph - h) / 2
        videoView.layout(left, top, left + w, top + h)
        fallbackSubtitleView.layout(left, top, left + w, top + h)
        subtitleGraphView.layout(left, top, left + w, top + h)
    }

    /**
     * 字幕 GL 层:SurfaceTexture(外部纹理)→ 预乘 shader → 透明 SurfaceView 层。
     * RENDERMODE_WHEN_DIRTY:VLC 只在字幕变化时提交字幕帧,不需要持续重绘。
     */
    private class SubtitleGlView(
        context: Context,
        onTextureReady: (SurfaceTexture) -> Unit,
    ) : GLSurfaceView(context) {

        private val renderer = SubtitleRenderer(onTextureReady) { requestRender() }

        init {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
        }
    }

    private class SubtitleRenderer(
        private val onTextureReady: (SurfaceTexture) -> Unit,
        private val requestRender: () -> Unit,
    ) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0
        private var aPos = 0
        private var aTex = 0
        private var uST = 0
        private var uTexture = 0
        private lateinit var posBuffer: FloatBuffer
        private lateinit var texBuffer: FloatBuffer
        private val stMatrix = FloatArray(16)
        @Volatile private var frameAvailable = false

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            program = buildProgram()
            if (program == 0) {
                Log.e(TAG, "subtitle shader build failed")
                return
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aTex = GLES20.glGetAttribLocation(program, "aTex")
            uST = GLES20.glGetUniformLocation(program, "uST")
            uTexture = GLES20.glGetUniformLocation(program, "sTexture")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )

            val st = SurfaceTexture(textureId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st
            onTextureReady(st)

            posBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            texBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            // WHEN_DIRTY 下先主动画一帧透明底,避免首帧未清屏时字幕层出现黑块
            requestRender()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val st = surfaceTexture ?: return
            if (program == 0 || !frameAvailable) return
            frameAvailable = false
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            GLES20.glUseProgram(program)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uTexture, 0)
            GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuffer)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aTex)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            frameAvailable = true
            requestRender()
        }

        private fun buildProgram(): Int {
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            if (vs == 0 || fs == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (status[0] == 0) {
                Log.e(TAG, "link program failed: " + GLES20.glGetProgramInfoLog(p))
                GLES20.glDeleteProgram(p)
                return 0
            }
            return p
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "compile shader failed: " + GLES20.glGetShaderInfoLog(s))
                GLES20.glDeleteShader(s)
                return 0
            }
            return s
        }

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(data); position(0) }

        companion object {
            private const val TAG = "AnidockTV"

            /**
             * VLC 的字幕面是 RGBA 直通 alpha;SurfaceFlinger 按预乘 alpha 合成。
             * 片元里 rgb *= a,把字幕边缘(ASS 半透明阴影/描边)压回正确的预乘值。
             */
            private const val VERTEX_SHADER = """
                attribute vec2 aPos;
                attribute vec2 aTex;
                uniform mat4 uST;
                varying vec2 vTex;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vTex = (uST * vec4(aTex, 0.0, 1.0)).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                uniform samplerExternalOES sTexture;
                varying vec2 vTex;
                void main() {
                    vec4 c = texture2D(sTexture, vTex);
                    gl_FragColor = vec4(c.rgb * c.a, c.a);
                }
            """
        }
    }
}