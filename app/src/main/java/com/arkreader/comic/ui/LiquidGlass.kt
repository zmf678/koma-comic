package com.arkreader.comic.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.arkreader.comic.R
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal enum class LiquidGlassPreset {
    FLOATING_DOCK,
    FROSTED_BAR,
    READER_CAPSULE,
    READER_DRAWER,
    CLEAR_LENS,
    SUBTLE_PILL,
    CARD_CAPTION
}

/**
 * Optical Liquid Glass drawable synchronized with Windows LiquidGlass.cs / LiquidGlass.hlsl:
 * - Edge-only rounded-rectangle SDF optical refraction (center stays undistorted)
 * - Subtle bevel chromatic dispersion (API 33+ AGSL GPU RuntimeShader with density-scaled rim)
 * - Downsampled frosted blur (for panels/docks) vs. crystal-clear lens (for floating orbs/buttons)
 * - Dual-angle opposing Fresnel specular rim + per-preset top caustic sheen + inner depth shadow
 * - Hardware-accelerated Canvas.drawBitmapMesh SDF refraction fallback for API 28-32
 * - Proper getOutline() for real rounded elevation shadows & outline clipping
 */
internal class LiquidGlassDrawable(
    private val density: Float,
    private val preset: LiquidGlassPreset,
    private val cornerRadiusDp: Float,
    private var dark: Boolean,
    private val forceDark: Boolean = false
) : Drawable() {

    private var backdropBitmap: Bitmap? = null
    private var backdropShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    private val gpuPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val boundsF = RectF()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outerLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fallbackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lightU = 0.18f
    private var lightV = 0.12f
    private var selectedState = false

    private var meshWidth = -1
    private var meshHeight = -1
    private var meshVerts: FloatArray? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private var runtimeShader: RuntimeShader? = null

    private val effectiveDark: Boolean
        get() = forceDark || dark

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimeShader = runCatching { RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull()
        }
        rebuildFallbackPaints()
    }

    fun setDarkMode(isDark: Boolean) {
        if (dark == isDark) return
        dark = isDark
        rebuildFallbackPaints()
        invalidateSelf()
    }

    fun setSelectedGlow(selected: Boolean) {
        if (selectedState == selected) return
        selectedState = selected
        rebuildFallbackPaints()
        invalidateSelf()
    }

    fun setLightPosition(u: Float, v: Float) {
        val nu = u.coerceIn(0f, 1f)
        val nv = v.coerceIn(0f, 1f)
        if (abs(nu - lightU) > 0.01f || abs(nv - lightV) > 0.01f) {
            lightU = nu
            lightV = nv
            invalidateSelf()
        }
    }

    fun resetLightPosition() {
        setLightPosition(0.18f, 0.12f)
    }

    fun updateBackdrop(bitmap: Bitmap?, invalidate: Boolean = true) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 2 || bitmap.height < 2) {
            backdropBitmap = null
            backdropShader = null
            if (invalidate) invalidateSelf()
            return
        }
        if (backdropBitmap !== bitmap) {
            backdropBitmap = bitmap
            backdropShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (invalidate) invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) {
            outline.setEmpty()
            return
        }
        val radius = resolvedCornerRadius(bounds.width().toFloat(), bounds.height().toFloat())
        outline.setRoundRect(bounds, radius)
        outline.alpha = 1f
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        boundsF.set(bounds)
        val radius = resolvedCornerRadius(boundsF.width(), boundsF.height())
        clipPath.reset()
        clipPath.addRoundRect(boundsF, radius, radius, Path.Direction.CW)
        meshWidth = -1
        rebuildFallbackPaints()
    }

    override fun draw(canvas: Canvas) {
        val w = boundsF.width()
        val h = boundsF.height()
        if (w < 2f || h < 2f) return

        val bmp = backdropBitmap
        val bmpShader = backdropShader
        val radius = resolvedCornerRadius(w, h)
        val bevel = resolvedBevelPx(w, h, radius)

        if (bmp != null && !bmp.isRecycled && bmpShader != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
                drawAgsl(canvas, bmp, bmpShader, w, h, radius, bevel)
                return
            }
            drawMeshFallback(canvas, bmp, w, h, radius, bevel)
            return
        }

        drawStaticOpticalGlass(canvas, radius)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgsl(
        canvas: Canvas,
        bmp: Bitmap,
        bmpShader: BitmapShader,
        w: Float,
        h: Float,
        radius: Float,
        bevel: Float
    ) {
        val shader = runtimeShader ?: return
        shaderMatrix.reset()
        shaderMatrix.setScale(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        bmpShader.setLocalMatrix(shaderMatrix)
        shader.setInputShader("backdrop", bmpShader)

        val spec = tuningFor(preset, effectiveDark, selectedState, density)
        shader.setFloatUniform("resolution", w, h)
        shader.setFloatUniform("densityScale", density)
        shader.setFloatUniform("cornerRadius", radius)
        shader.setFloatUniform("bevelWidth", bevel)
        shader.setFloatUniform("refractionStrength", spec.refractionPx)
        shader.setFloatUniform("chromaticPx", spec.chromaticPx)
        shader.setFloatUniform("opticalBoost", spec.opticalBoost)
        shader.setFloatUniform("lightPos", lightU, lightV)
        shader.setFloatUniform("rimIntensity", spec.rimIntensity)
        shader.setFloatUniform("sheenStrength", spec.sheenStrength)
        shader.setFloatUniform("innerShadowOpacity", spec.innerShadowOpacity)
        shader.setFloatUniform("outerDarkLipOpacity", spec.outerDarkLipOpacity)
        shader.setColorUniform("tintColor", spec.tintColor)
        shader.setColorUniform("specularColor", spec.specularColor)
        shader.setColorUniform("secondaryRimColor", spec.secondaryRimColor)

        gpuPaint.shader = shader
        val save = canvas.save()
        canvas.translate(boundsF.left, boundsF.top)
        canvas.drawRect(0f, 0f, w, h, gpuPaint)
        canvas.restoreToCount(save)
    }

    private fun drawMeshFallback(
        canvas: Canvas,
        bmp: Bitmap,
        w: Float,
        h: Float,
        radius: Float,
        bevel: Float
    ) {
        val spec = tuningFor(preset, effectiveDark, selectedState, density)
        val iw = w.toInt()
        val ih = h.toInt()
        if (meshWidth != iw || meshHeight != ih || meshVerts == null) {
            meshVerts = buildSdfRefractionMesh(w, h, radius, bevel, spec.refractionPx)
            meshWidth = iw
            meshHeight = ih
        }
        val verts = meshVerts ?: return

        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(boundsF.left, boundsF.top)
        canvas.drawBitmapMesh(bmp, MESH_COLS, MESH_ROWS, verts, 0, null, 0, null)
        canvas.restoreToCount(save)

        drawOpticalOverlays(canvas, radius, spec)
    }

    private fun drawStaticOpticalGlass(canvas: Canvas, radius: Float) {
        val spec = tuningFor(preset, effectiveDark, selectedState, density)
        canvas.drawRoundRect(boundsF, radius, radius, fallbackBgPaint)
        drawOpticalOverlays(canvas, radius, spec)
    }

    private fun drawOpticalOverlays(
        canvas: Canvas,
        radius: Float,
        spec: GlassTuning
    ) {
        tintPaint.color = spec.tintColor
        canvas.drawRoundRect(boundsF, radius, radius, tintPaint)

        canvas.drawRoundRect(boundsF, radius, radius, innerShadowPaint)
        canvas.drawRoundRect(boundsF, radius, radius, sheenPaint)

        // Subtle outer grazing dark lip (visible on light mode for glass edge separation)
        if (spec.outerDarkLipOpacity > 0.01f) {
            val lipInset = max(0.5f * density, 0.75f)
            outerLipPaint.strokeWidth = max(1.0f * density, 1f)
            outerLipPaint.color = Color.argb(
                (255f * spec.outerDarkLipOpacity * 0.72f).toInt().coerceIn(0, 96),
                12, 24, 48
            )
            val lipRect = RectF(
                boundsF.left + lipInset,
                boundsF.top + lipInset,
                boundsF.right - lipInset,
                boundsF.bottom - lipInset
            )
            canvas.drawRoundRect(lipRect, max(0f, radius - lipInset), max(0f, radius - lipInset), outerLipPaint)
        }

        // Crisp dual-angle Fresnel rim stroke
        val inset = max(0.85f * density, 1f)
        rimPaint.strokeWidth = inset * 1.3f
        val rimRect = RectF(
            boundsF.left + inset * 0.65f,
            boundsF.top + inset * 0.65f,
            boundsF.right - inset * 0.65f,
            boundsF.bottom - inset * 0.65f
        )
        canvas.drawRoundRect(rimRect, max(0f, radius - inset * 0.55f), max(0f, radius - inset * 0.55f), rimPaint)
    }

    private fun rebuildFallbackPaints() {
        val w = max(boundsF.width(), 48f * density)
        val h = max(boundsF.height(), 48f * density)
        val d = effectiveDark
        val spec = tuningFor(preset, d, selectedState, density)

        fallbackBgPaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.left + w, boundsF.top + h,
            when {
                selectedState && d -> intArrayOf(0xB442A2FF.toInt(), 0x862466C6.toInt())
                selectedState -> intArrayOf(0xECE0F1FF.toInt(), 0xD8C2E0FF.toInt())
                preset == LiquidGlassPreset.CLEAR_LENS -> intArrayOf(0xB84673A8.toInt(), 0xA81D3354.toInt())
                d -> intArrayOf(0xCC1E2C45.toInt(), 0xBA111A2C.toInt())
                else -> intArrayOf(0xE4FFFFFF.toInt(), 0xC8ECF4FF.toInt())
            },
            null,
            Shader.TileMode.CLAMP
        )

        sheenPaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.left, boundsF.top + h * 0.58f,
            intArrayOf(
                Color.argb((Color.alpha(spec.specularColor) * spec.sheenStrength).toInt().coerceIn(0, 220), 255, 255, 255),
                0x00FFFFFF
            ),
            null,
            Shader.TileMode.CLAMP
        )

        innerShadowPaint.shader = LinearGradient(
            boundsF.left, boundsF.top + h * 0.52f, boundsF.left, boundsF.top + h,
            intArrayOf(
                0x00000000,
                Color.argb((255f * spec.innerShadowOpacity).toInt().coerceIn(0, 120), 4, 10, 22)
            ),
            null,
            Shader.TileMode.CLAMP
        )

        rimPaint.shader = LinearGradient(
            boundsF.left, boundsF.top, boundsF.left + w, boundsF.top + h,
            intArrayOf(
                spec.specularColor,
                Color.argb(if (d) 40 else 124, 220, 234, 255),
                spec.secondaryRimColor
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun resolvedCornerRadius(w: Float, h: Float): Float {
        return min(cornerRadiusDp * density, min(w, h) * 0.5f)
    }

    private fun resolvedBevelPx(w: Float, h: Float, radius: Float): Float {
        val minDim = min(w, h)
        return when (preset) {
            LiquidGlassPreset.CLEAR_LENS -> (minDim * 0.44f).coerceIn(10f * density, 22f * density)
            LiquidGlassPreset.READER_DRAWER -> 20f * density
            LiquidGlassPreset.SUBTLE_PILL -> (minDim * 0.36f).coerceIn(8f * density, 14f * density)
            else -> max(14f * density, radius * 0.60f).coerceAtMost(minDim * 0.38f)
        }
    }

    override fun setAlpha(alpha: Int) {
        gpuPaint.alpha = alpha
        fallbackBgPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        gpuPaint.colorFilter = colorFilter
        fallbackBgPaint.colorFilter = colorFilter
    }

    @Deprecated("Android drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private data class GlassTuning(
        val refractionPx: Float,
        val chromaticPx: Float,
        val opticalBoost: Float,
        val rimIntensity: Float,
        val sheenStrength: Float,
        val innerShadowOpacity: Float,
        val outerDarkLipOpacity: Float,
        val tintColor: Int,
        val specularColor: Int,
        val secondaryRimColor: Int
    )

    companion object {
        private const val MESH_COLS = 16
        private const val MESH_ROWS = 16

        private fun tuningFor(
            preset: LiquidGlassPreset,
            dark: Boolean,
            selected: Boolean,
            density: Float
        ): GlassTuning {
            if (selected) {
                return GlassTuning(
                    refractionPx = 16f * density,
                    chromaticPx = 2.6f * density,
                    opticalBoost = 1.16f,
                    rimIntensity = 1.30f,
                    sheenStrength = if (dark) 0.28f else 0.42f,
                    innerShadowOpacity = if (dark) 0.16f else 0.14f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.22f,
                    tintColor = if (dark) 0x783A9BFF else 0x86CBE8FF.toInt(),
                    specularColor = 0xFAFFFFFF.toInt(),
                    secondaryRimColor = 0xC87AC4FF.toInt()
                )
            }
            return when (preset) {
                LiquidGlassPreset.CLEAR_LENS -> GlassTuning(
                    refractionPx = 26.0f * density,
                    chromaticPx = 3.8f * density,
                    opticalBoost = 1.24f,
                    rimIntensity = 1.34f,
                    sheenStrength = 0.28f,
                    innerShadowOpacity = 0.24f,
                    outerDarkLipOpacity = 0.14f,
                    tintColor = 0x58182E52,
                    specularColor = 0xFAFFFFFF.toInt(),
                    secondaryRimColor = 0xD676CCFF.toInt()
                )
                LiquidGlassPreset.READER_CAPSULE -> GlassTuning(
                    refractionPx = 26.0f * density,
                    chromaticPx = 3.4f * density,
                    opticalBoost = 1.15f,
                    rimIntensity = 1.26f,
                    sheenStrength = 0.24f,
                    innerShadowOpacity = 0.24f,
                    outerDarkLipOpacity = 0.12f,
                    tintColor = 0x760E1626,
                    specularColor = 0xF2FFFFFF.toInt(),
                    secondaryRimColor = 0xB86EC2FF.toInt()
                )
                LiquidGlassPreset.READER_DRAWER -> GlassTuning(
                    refractionPx = 18.0f * density,
                    chromaticPx = 2.4f * density,
                    opticalBoost = 1.00f,
                    rimIntensity = 1.08f,
                    sheenStrength = 0.16f,
                    innerShadowOpacity = 0.18f,
                    outerDarkLipOpacity = 0.10f,
                    tintColor = 0xA60E1523.toInt(),
                    specularColor = 0xD8FFFFFF.toInt(),
                    secondaryRimColor = 0x9870B8FF.toInt()
                )
                LiquidGlassPreset.FLOATING_DOCK -> GlassTuning(
                    refractionPx = 28.0f * density,
                    chromaticPx = 3.6f * density,
                    opticalBoost = if (dark) 1.16f else 1.10f,
                    rimIntensity = if (dark) 1.28f else 1.36f,
                    sheenStrength = if (dark) 0.24f else 0.42f,
                    innerShadowOpacity = if (dark) 0.22f else 0.18f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.26f,
                    tintColor = if (dark) 0x58121E36 else 0x3EF2F9FF,
                    specularColor = if (dark) 0xF4FFFFFF.toInt() else 0xFFFFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xBC6CC0FF.toInt() else 0xD660A6FF.toInt()
                )
                LiquidGlassPreset.SUBTLE_PILL -> GlassTuning(
                    refractionPx = 22.0f * density,
                    chromaticPx = 3.0f * density,
                    opticalBoost = 1.12f,
                    rimIntensity = if (dark) 1.20f else 1.32f,
                    sheenStrength = if (dark) 0.22f else 0.36f,
                    innerShadowOpacity = if (dark) 0.18f else 0.16f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.24f,
                    tintColor = if (dark) 0x62121C30 else 0x42F4FAFF,
                    specularColor = 0xF8FFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xB072C2FF.toInt() else 0xCC66AAFF.toInt()
                )
                LiquidGlassPreset.FROSTED_BAR -> GlassTuning(
                    refractionPx = 26.0f * density,
                    chromaticPx = 3.4f * density,
                    opticalBoost = if (dark) 1.14f else 1.08f,
                    rimIntensity = if (dark) 1.24f else 1.34f,
                    sheenStrength = if (dark) 0.22f else 0.38f,
                    innerShadowOpacity = if (dark) 0.20f else 0.17f,
                    outerDarkLipOpacity = if (dark) 0.10f else 0.25f,
                    tintColor = if (dark) 0x60121D32 else 0x44F4FAFF,
                    specularColor = if (dark) 0xF0FFFFFF.toInt() else 0xFFFFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xB470C0FF.toInt() else 0xD062A8FF.toInt()
                )
                LiquidGlassPreset.CARD_CAPTION -> GlassTuning(
                    refractionPx = 22.0f * density,
                    chromaticPx = 3.2f * density,
                    opticalBoost = if (dark) 1.14f else 1.06f,
                    rimIntensity = if (dark) 1.26f else 1.34f,
                    sheenStrength = if (dark) 0.24f else 0.38f,
                    innerShadowOpacity = if (dark) 0.22f else 0.16f,
                    outerDarkLipOpacity = if (dark) 0.12f else 0.24f,
                    tintColor = if (dark) 0x8E10192C.toInt() else 0x94F8FBFF.toInt(),
                    specularColor = 0xFCFFFFFF.toInt(),
                    secondaryRimColor = if (dark) 0xB66EC2FF.toInt() else 0xCC62A8FF.toInt()
                )
            }
        }

        private fun roundedRectSdf(px: Float, py: Float, halfW: Float, halfH: Float, radius: Float): Float {
            val qx = abs(px) - halfW + radius
            val qy = abs(py) - halfH + radius
            val outX = max(qx, 0f)
            val outY = max(qy, 0f)
            return min(max(qx, qy), 0f) + sqrt(outX * outX + outY * outY) - radius
        }

        private fun buildSdfRefractionMesh(
            w: Float,
            h: Float,
            radius: Float,
            bevel: Float,
            refractionPx: Float
        ): FloatArray {
            val verts = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)
            val halfW = w * 0.5f
            val halfH = h * 0.5f
            var idx = 0
            for (r in 0..MESH_ROWS) {
                val v = r / MESH_ROWS.toFloat()
                val y = v * h
                for (c in 0..MESH_COLS) {
                    val u = c / MESH_COLS.toFloat()
                    val x = u * w
                    val px = x - halfW
                    val py = y - halfH
                    val sdf = roundedRectSdf(px, py, halfW, halfH, radius)
                    val distIn = max(-sdf, 0f)
                    val edgeT = (1f - distIn / max(bevel, 2f)).coerceIn(0f, 1f)
                    val steep = edgeT.toDouble().pow(2.4).toFloat()
                    if (steep > 0.001f) {
                        val eps = 1.5f
                        val gx = roundedRectSdf(px + eps, py, halfW, halfH, radius) -
                            roundedRectSdf(px - eps, py, halfW, halfH, radius)
                        val gy = roundedRectSdf(px, py + eps, halfW, halfH, radius) -
                            roundedRectSdf(px, py - eps, halfW, halfH, radius)
                        val len = sqrt(gx * gx + gy * gy)
                        if (len > 0.0001f) {
                            val disp = refractionPx * steep * 0.72f
                            verts[idx++] = x + (gx / len) * disp
                            verts[idx++] = y + (gy / len) * disp
                        } else {
                            verts[idx++] = x
                            verts[idx++] = y
                        }
                    } else {
                        verts[idx++] = x
                        verts[idx++] = y
                    }
                }
            }
            return verts
        }

        // AGSL shader aligned with windows/LiquidGlass.hlsl (density-scaled rim & per-preset caustic sheen)
        private const val LIQUID_GLASS_AGSL = """
            uniform shader backdrop;
            uniform float2 resolution;
            uniform float densityScale;
            uniform float cornerRadius;
            uniform float bevelWidth;
            uniform float refractionStrength;
            uniform float chromaticPx;
            uniform float opticalBoost;
            uniform float2 lightPos;
            uniform float rimIntensity;
            uniform float sheenStrength;
            uniform float innerShadowOpacity;
            uniform float outerDarkLipOpacity;
            layout(color) uniform half4 tintColor;
            layout(color) uniform half4 specularColor;
            layout(color) uniform half4 secondaryRimColor;

            float roundedRectSDF(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + radius;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            float2 roundedRectNormal(float2 p, float2 halfSize, float radius, float eps) {
                float gx = roundedRectSDF(p + float2(eps, 0.0), halfSize, radius)
                         - roundedRectSDF(p - float2(eps, 0.0), halfSize, radius);
                float gy = roundedRectSDF(p + float2(0.0, eps), halfSize, radius)
                         - roundedRectSDF(p - float2(0.0, eps), halfSize, radius);
                float2 g = float2(gx, gy);
                float len = length(g);
                return len > 0.0001 ? (g / len) : float2(0.0, -1.0);
            }

            half3 applyOpticalTone(half3 rgb, float boost) {
                half luma = dot(rgb, half3(0.299, 0.587, 0.114));
                half3 sat = mix(half3(luma), rgb, half(1.0 + 0.18 * boost));
                half3 con = (sat - half(0.5)) * half(1.0 + 0.12 * boost) + half(0.5);
                return clamp(con * half(1.0 + 0.045 * boost), half3(0.0), half3(1.0));
            }

            half4 main(float2 fragCoord) {
                float2 size = max(resolution, float2(2.0, 2.0));
                float2 halfSize = size * 0.5;
                float radius = min(cornerRadius, min(halfSize.x, halfSize.y));
                float bevel = max(bevelWidth, 2.0);
                float dScale = max(densityScale, 1.0);

                float2 p = fragCoord - halfSize;
                float sdf = roundedRectSDF(p, halfSize, radius);
                float shapeAlpha = clamp(0.5 - sdf, 0.0, 1.0);
                if (shapeAlpha <= 0.001) {
                    return half4(0.0);
                }

                float distIn = max(-sdf, 0.0);
                float edgeT = clamp(1.0 - distIn / bevel, 0.0, 1.0);
                float meniscus = edgeT * edgeT;
                float steepCurve = edgeT * edgeT * edgeT;

                float2 normal = roundedRectNormal(p, halfSize, radius, max(1.0, dScale * 0.85));
                float2 refractPos = clamp(fragCoord - normal * (refractionStrength * steepCurve), float2(0.0), size);
                float2 chromaOff = -normal * (chromaticPx * steepCurve);

                half r = backdrop.eval(clamp(refractPos + chromaOff, float2(0.0), size)).r;
                half g = backdrop.eval(refractPos).g;
                half b = backdrop.eval(clamp(refractPos - chromaOff, float2(0.0), size)).b;
                half3 baseRgb = applyOpticalTone(half3(r, g, b), opticalBoost);

                half tintFactor = clamp(tintColor.a * half(1.0 - 0.45 * meniscus), half(0.0), half(1.0));
                half3 glassRgb = mix(baseRgb, tintColor.rgb, tintFactor);

                float2 uv = fragCoord / size;
                float outerRim = smoothstep(bevel * 0.36, 0.0, distIn);
                float crispRim = smoothstep(2.08 * dScale, 0.0, distIn);
                float depthShade = innerShadowOpacity * (uv.y * 0.52 + meniscus * 0.52) + outerDarkLipOpacity * crispRim;
                glassRgb *= half(clamp(1.0 - depthShade, 0.0, 1.0));

                float topSheen = pow(clamp(1.0 - uv.y * 1.9, 0.0, 1.0), 2.0) * (sheenStrength * (0.68 + 0.32 * (1.0 - edgeT)));
                glassRgb += specularColor.rgb * half(topSheen * float(specularColor.a) * rimIntensity);

                float2 lightDir = normalize((lightPos - 0.5) * size - p + float2(-0.001, -0.001));
                float2 keyLight = normalize(mix(normalize(float2(-0.68, -0.73)), lightDir, 0.45));
                float keyAlign = clamp(dot(normal, keyLight) * 0.65 + 0.35, 0.0, 1.0);
                float oppAlign = clamp(dot(normal, -keyLight) * 0.70 + 0.30, 0.0, 1.0);

                float primarySpec = (crispRim * 0.85 + outerRim * 0.26) * pow(keyAlign, 1.45) * rimIntensity;
                float secondarySpec = (crispRim * 0.58 + outerRim * 0.22) * pow(oppAlign, 1.75) * rimIntensity;

                glassRgb += specularColor.rgb * half(primarySpec * float(specularColor.a));
                glassRgb += secondaryRimColor.rgb * half(secondarySpec * float(secondaryRimColor.a));
                glassRgb = clamp(glassRgb, half3(0.0), half3(1.0));

                return half4(glassRgb * half(shapeAlpha), half(shapeAlpha));
            }
        """
    }
}

/**
 * Attaches live backdrop sampling + touch-reactive optical glint & elastic feedback to any View.
 * Uses fingerprint change detection & pooled blur buffers to avoid idle CPU redraw loops.
 */
internal class LiquidGlassBinder(
    private val targetView: View,
    private val backdropProvider: () -> View?,
    val drawable: LiquidGlassDrawable,
    private val preset: LiquidGlassPreset,
    private val fallbackBaseColor: Int,
    private val interactiveElastic: Boolean = false
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, View.OnLayoutChangeListener {

    private var captureBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var blurPixels: IntArray? = null
    private var blurTmp: IntArray? = null
    private val targetLoc = IntArray(2)
    private val backdropLoc = IntArray(2)
    private var lastCaptureMs = 0L
    private var lastFingerprint = 0L
    private var lastDx = Float.NaN
    private var lastDy = Float.NaN
    private var lastW = -1
    private var lastH = -1
    private var isSampling = false
    private var attached = false
    private var dirty = true

    fun attach() {
        if (attached) return
        attached = true
        targetView.addOnAttachStateChangeListener(this)
        targetView.addOnLayoutChangeListener(this)
        if (targetView.isAttachedToWindow) {
            targetView.viewTreeObserver.addOnPreDrawListener(this)
        }
        if (interactiveElastic) {
            attachTouchFeedback()
        }
        targetView.post { captureNow(force = true) }
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (targetView.viewTreeObserver.isAlive) {
            targetView.viewTreeObserver.removeOnPreDrawListener(this)
        }
        targetView.removeOnAttachStateChangeListener(this)
        targetView.removeOnLayoutChangeListener(this)
        drawable.updateBackdrop(null, invalidate = false)
        captureBitmap?.recycle()
        captureBitmap = null
        captureCanvas = null
        blurPixels = null
        blurTmp = null
    }

    fun markDirty() {
        dirty = true
    }

    override fun onLayoutChange(
        v: View?, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
    ) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            dirty = true
        }
    }

    override fun onViewAttachedToWindow(v: View) {
        if (v.viewTreeObserver.isAlive) {
            v.viewTreeObserver.removeOnPreDrawListener(this)
            v.viewTreeObserver.addOnPreDrawListener(this)
        }
        dirty = true
        captureNow(force = true)
    }

    override fun onViewDetachedFromWindow(v: View) {
        if (v.viewTreeObserver.isAlive) {
            v.viewTreeObserver.removeOnPreDrawListener(this)
        }
    }

    override fun onPreDraw(): Boolean {
        captureNow(force = false)
        return true
    }

    fun captureNow(force: Boolean) {
        val backdropView = backdropProvider() ?: return
        if (isSampling || targetView.visibility != View.VISIBLE || backdropView.visibility != View.VISIBLE) return
        val w = targetView.width
        val h = targetView.height
        if (w < 4 || h < 4 || backdropView.width < 4 || backdropView.height < 4) return

        targetView.getLocationInWindow(targetLoc)
        backdropView.getLocationInWindow(backdropLoc)
        val dx = (targetLoc[0] - backdropLoc[0]).toFloat()
        val dy = (targetLoc[1] - backdropLoc[1]).toFloat()
        val geometryChanged = dx != lastDx || dy != lastDy || w != lastW || h != lastH

        val now = SystemClock.uptimeMillis()
        val minInterval = if (preset == LiquidGlassPreset.CLEAR_LENS) 32L else 48L
        if (!force && !dirty && !geometryChanged) {
            // Never stall scrolling with periodic onPreDraw captures; refresh explicitly on idle/page change
            return
        } else if (!force && now - lastCaptureMs < minInterval) {
            return
        }
        lastCaptureMs = now
        dirty = false
        lastDx = dx
        lastDy = dy
        lastW = w
        lastH = h

        val scale = when (preset) {
            LiquidGlassPreset.CLEAR_LENS -> 0.85f
            LiquidGlassPreset.SUBTLE_PILL -> 0.45f
            else -> 0.28f
        }
        val bw = (w * scale).toInt().coerceIn(8, 360)
        val bh = (h * scale).toInt().coerceIn(8, 360)

        var bmp = captureBitmap
        var oldToRecycle: Bitmap? = null
        if (bmp == null || bmp.isRecycled || bmp.width != bw || bmp.height != bh) {
            oldToRecycle = bmp
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            captureBitmap = bmp
            captureCanvas = Canvas(bmp)
        }
        val canvas = captureCanvas ?: return

        isSampling = true
        val prevVisibility = targetView.visibility
        val sameSubtree = isDescendantOf(targetView, backdropView)
        try {
            if (sameSubtree) targetView.visibility = View.INVISIBLE
            bmp.eraseColor(fallbackBaseColor)
            val save = canvas.save()
            canvas.scale(bw / w.toFloat(), bh / h.toFloat())
            canvas.translate(-dx, -dy)
            backdropView.draw(canvas)
            canvas.restoreToCount(save)

            val fp = computeSampleFingerprint(bmp)
            if (!force && !geometryChanged && oldToRecycle == null && fp == lastFingerprint) {
                return
            }
            lastFingerprint = fp

            val blurRadius = when (preset) {
                LiquidGlassPreset.CLEAR_LENS -> 0
                LiquidGlassPreset.SUBTLE_PILL -> 2
                LiquidGlassPreset.FLOATING_DOCK -> 4
                LiquidGlassPreset.FROSTED_BAR -> 4
                LiquidGlassPreset.READER_CAPSULE -> 5
                LiquidGlassPreset.READER_DRAWER -> 7
                LiquidGlassPreset.CARD_CAPTION -> 3
            }
            if (blurRadius > 0) {
                val needed = bw * bh
                var pBuf = blurPixels
                var tBuf = blurTmp
                if (pBuf == null || pBuf.size < needed) {
                    pBuf = IntArray(needed)
                    tBuf = IntArray(needed)
                    blurPixels = pBuf
                    blurTmp = tBuf
                }
                fastBoxBlur(bmp, blurRadius, pBuf, tBuf!!)
            }
            drawable.updateBackdrop(bmp, invalidate = force || geometryChanged || oldToRecycle != null)
            oldToRecycle?.recycle()
        } catch (_: Throwable) {
            // Keep previous frame gracefully on transient hardware canvas states
        } finally {
            if (sameSubtree) targetView.visibility = prevVisibility
            isSampling = false
        }
    }

    private fun computeSampleFingerprint(bmp: Bitmap): Long {
        val w = bmp.width
        val h = bmp.height
        var hash = 1125899906842597L
        for (ry in 0..3) {
            val y = ((ry + 0.5f) * h / 4f).toInt().coerceIn(0, h - 1)
            for (rx in 0..3) {
                val x = ((rx + 0.5f) * w / 4f).toInt().coerceIn(0, w - 1)
                hash = hash * 31L + bmp.getPixel(x, y).toLong()
            }
        }
        return hash
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchFeedback() {
        targetView.setOnTouchListener { v, event ->
            val w = max(v.width.toFloat(), 1f)
            val h = max(v.height.toFloat(), 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawable.setLightPosition(event.x / w, event.y / h)
                    animateElastic(v, 0.95f, 90L)
                }
                MotionEvent.ACTION_MOVE -> {
                    drawable.setLightPosition(event.x / w, event.y / h)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    drawable.resetLightPosition()
                    animateElastic(v, 1.0f, 180L)
                }
            }
            false
        }
    }

    private fun animateElastic(view: View, scale: Float, durationMs: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(durationMs)
            .start()
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var cur: Any? = child
        while (cur is View) {
            if (cur === ancestor) return true
            cur = cur.parent
        }
        return false
    }

    companion object {
        internal fun blurBitmapInPlace(bitmap: Bitmap, radius: Int) {
            val size = bitmap.width * bitmap.height
            if (size < 9 || radius < 1) return
            fastBoxBlur(bitmap, radius, IntArray(size), IntArray(size))
        }

        private fun fastBoxBlur(bitmap: Bitmap, radius: Int, pixels: IntArray, tmp: IntArray) {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 3 || h < 3 || radius < 1) return
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val r = radius.coerceIn(1, min(w, h) / 2)
            val div = r * 2 + 1

            repeat(2) {
                for (y in 0 until h) {
                    val row = y * w
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    for (i in -r..r) {
                        val c = pixels[row + i.coerceIn(0, w - 1)]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                    }
                    for (x in 0 until w) {
                        tmp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                        val oldC = pixels[row + (x - r).coerceIn(0, w - 1)]
                        val newC = pixels[row + (x + r + 1).coerceIn(0, w - 1)]
                        rSum += ((newC shr 16) and 0xFF) - ((oldC shr 16) and 0xFF)
                        gSum += ((newC shr 8) and 0xFF) - ((oldC shr 8) and 0xFF)
                        bSum += (newC and 0xFF) - (oldC and 0xFF)
                    }
                }
                for (x in 0 until w) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    for (i in -r..r) {
                        val c = tmp[i.coerceIn(0, h - 1) * w + x]
                        rSum += (c shr 16) and 0xFF
                        gSum += (c shr 8) and 0xFF
                        bSum += c and 0xFF
                    }
                    for (y in 0 until h) {
                        pixels[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                        val oldC = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                        val newC = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                        rSum += ((newC shr 16) and 0xFF) - ((oldC shr 16) and 0xFF)
                        gSum += ((newC shr 8) and 0xFF) - ((oldC shr 8) and 0xFF)
                        bSum += (newC and 0xFF) - (oldC and 0xFF)
                    }
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}

/**
 * 4-orb ambient gradient backdrop synchronized with Windows BuildAmbientOrbLayer().
 */
internal class AmbientGlassCanvasDrawable(private val dark: Boolean) : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb1Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb2Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb3Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb4Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = max(bounds.width().toFloat(), 1f)
        val h = max(bounds.height().toFloat(), 1f)
        val span = max(w, h)
        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            if (dark) intArrayOf(0xFF0A0F1C.toInt(), 0xFF0F172A.toInt(), 0xFF131B30.toInt())
            else intArrayOf(0xFFF0F5FD.toInt(), 0xFFEAF1FC.toInt(), 0xFFF3EFFB.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // Orb 1: Top-left blue (#943A9EFF)
        orb1Paint.shader = RadialGradient(
            w * 0.10f, h * 0.12f, span * 0.48f,
            if (dark) 0x823A9EFF.toInt() else 0x743A9EFF,
            0x003A9EFF,
            Shader.TileMode.CLAMP
        )
        // Orb 2: Center-left indigo/purple (#888A6EFF)
        orb2Paint.shader = RadialGradient(
            w * 0.14f, h * 0.62f, span * 0.44f,
            if (dark) 0x768A6EFF else 0x6A8A6EFF,
            0x008A6EFF,
            Shader.TileMode.CLAMP
        )
        // Orb 3: Top-right cyan (#8022C5F6)
        orb3Paint.shader = RadialGradient(
            w * 0.88f, h * 0.14f, span * 0.50f,
            if (dark) 0x7022C5F6 else 0x6422C5F6,
            0x0022C5F6,
            Shader.TileMode.CLAMP
        )
        // Orb 4: Bottom-right violet (#6CC484FC)
        orb4Paint.shader = RadialGradient(
            w * 0.86f, h * 0.86f, span * 0.46f,
            if (dark) 0x60C484FC else 0x56C484FC,
            0x00C484FC,
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, bgPaint)
        canvas.drawRect(bounds, orb1Paint)
        canvas.drawRect(bounds, orb2Paint)
        canvas.drawRect(bounds, orb3Paint)
        canvas.drawRect(bounds, orb4Paint)
    }

    override fun setAlpha(alpha: Int) { bgPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { bgPaint.colorFilter = colorFilter }
    @Deprecated("Android drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Floating Liquid Glass vertical scrollbar thumb overlay for RecyclerViews,
 * synchronized with Windows' floating glass ScrollBar (v1.5.5+).
 */
internal class LiquidGlassScrollbarDecoration(
    private val recyclerView: RecyclerView
) : RecyclerView.ItemDecoration() {

    private val density = recyclerView.resources.displayMetrics.density
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRect = RectF()
    private val glossRect = RectF()
    private var alphaProgress = 0f
    private var scrolling = false
    private var fadeAnim: ValueAnimator? = null

    private val hideRunnable = Runnable {
        scrolling = false
        animateAlphaTo(0f, 360L)
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) showThumb()
        }

        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                showThumb()
            } else {
                scheduleHide()
            }
        }
    }

    init {
        recyclerView.addOnScrollListener(scrollListener)
    }

    fun detach() {
        recyclerView.removeCallbacks(hideRunnable)
        fadeAnim?.cancel()
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeItemDecoration(this)
    }

    private fun showThumb() {
        recyclerView.removeCallbacks(hideRunnable)
        scrolling = true
        if (alphaProgress < 0.99f) {
            animateAlphaTo(1f, 120L)
        } else {
            recyclerView.invalidate()
        }
        scheduleHide()
    }

    private fun scheduleHide() {
        recyclerView.removeCallbacks(hideRunnable)
        recyclerView.postDelayed(hideRunnable, 1100L)
    }

    private fun animateAlphaTo(target: Float, duration: Long) {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(alphaProgress, target).apply {
            this.duration = duration
            addUpdateListener {
                alphaProgress = it.animatedValue as Float
                recyclerView.invalidate()
            }
            start()
        }
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (alphaProgress <= 0.01f) return
        val range = parent.computeVerticalScrollRange()
        val extent = parent.computeVerticalScrollExtent()
        if (range <= extent || extent <= 0) return
        val offset = parent.computeVerticalScrollOffset()

        val dark = (parent.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val trackTop = parent.paddingTop + 8f * density
        val trackBottom = (parent.height - parent.paddingBottom - 8f * density).coerceAtLeast(trackTop + 48f * density)
        val trackH = trackBottom - trackTop
        val minThumbH = 38f * density
        val thumbH = max(minThumbH, trackH * (extent.toFloat() / range.toFloat())).coerceAtMost(trackH * 0.72f)
        val scrollable = (range - extent).coerceAtLeast(1)
        val ratio = (offset.toFloat() / scrollable.toFloat()).coerceIn(0f, 1f)
        val top = trackTop + ratio * (trackH - thumbH)

        val thumbW = (if (scrolling) 8.5f else 6.5f) * density
        val right = parent.width - 5f * density
        val left = right - thumbW
        val radius = thumbW * 0.5f
        thumbRect.set(left, top, right, top + thumbH)

        val a = (alphaProgress * 255f).toInt().coerceIn(0, 255)
        thumbPaint.shader = LinearGradient(
            left, top, right, top + thumbH,
            if (dark) intArrayOf(0xB856B4FF.toInt(), 0x942D7AE6.toInt())
            else intArrayOf(0xC83A9BFF.toInt(), 0xA81D72DA.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        thumbPaint.alpha = a
        canvas.drawRoundRect(thumbRect, radius, radius, thumbPaint)

        // Top gloss highlight matching Windows Liquid Glass scrollbar
        val glossH = min(16f * density, thumbH * 0.35f)
        glossRect.set(left + density * 0.8f, top + density * 0.8f, right - density * 0.8f, top + glossH)
        glossPaint.shader = LinearGradient(
            left, top, left, top + glossH,
            intArrayOf(0x78FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )
        glossPaint.alpha = a
        canvas.drawRoundRect(glossRect, radius, radius, glossPaint)

        rimPaint.strokeWidth = max(0.9f * density, 1f)
        rimPaint.color = if (dark) 0x96FFFFFF.toInt() else 0xD8FFFFFF.toInt()
        rimPaint.alpha = (a * 0.75f).toInt()
        canvas.drawRoundRect(thumbRect, radius, radius, rimPaint)
    }
}

internal fun RecyclerView.attachLiquidGlassScrollbar() {
    val existing = getTag(R.id.tag_glass_scrollbar) as? LiquidGlassScrollbarDecoration
    if (existing != null) return
    isVerticalScrollBarEnabled = false
    val deco = LiquidGlassScrollbarDecoration(this)
    addItemDecoration(deco)
    setTag(R.id.tag_glass_scrollbar, deco)
}

internal fun View.applyLiquidGlass(
    backdropProvider: () -> View?,
    preset: LiquidGlassPreset,
    cornerRadiusDp: Float,
    forceDark: Boolean = false,
    interactiveElastic: Boolean = false,
    elevationDp: Float = 6f
): LiquidGlassDrawable {
    detachLiquidGlass()
    val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val effectiveDark = forceDark || dark
    val drawable = LiquidGlassDrawable(
        density = resources.displayMetrics.density,
        preset = preset,
        cornerRadiusDp = cornerRadiusDp,
        dark = dark,
        forceDark = forceDark
    )
    background = drawable
    elevation = elevationDp * resources.displayMetrics.density
    val baseColor = if (effectiveDark) 0xFF0A0F1C.toInt() else 0xFFEAF1FC.toInt()
    val binder = LiquidGlassBinder(this, backdropProvider, drawable, preset, baseColor, interactiveElastic)
    setTag(R.id.tag_liquid_glass_binder, binder)
    binder.attach()
    return drawable
}

internal fun View.applyLiquidGlass(
    backdropView: View?,
    preset: LiquidGlassPreset,
    cornerRadiusDp: Float,
    forceDark: Boolean = false,
    interactiveElastic: Boolean = false,
    elevationDp: Float = 6f
): LiquidGlassDrawable = applyLiquidGlass(
    backdropProvider = { backdropView },
    preset = preset,
    cornerRadiusDp = cornerRadiusDp,
    forceDark = forceDark,
    interactiveElastic = interactiveElastic,
    elevationDp = elevationDp
)

internal fun View.detachLiquidGlass() {
    val existing = getTag(R.id.tag_liquid_glass_binder) as? LiquidGlassBinder
    if (existing != null) {
        existing.detach()
        setTag(R.id.tag_liquid_glass_binder, null)
    }
}

internal fun View.refreshLiquidGlassBackdrop() {
    val binder = getTag(R.id.tag_liquid_glass_binder) as? LiquidGlassBinder ?: return
    binder.markDirty()
    binder.captureNow(force = true)
}

private val captionSliceCache = object : LruCache<Int, Bitmap>(96) {}
private val captionBlurExecutor = Executors.newFixedThreadPool(2)
private val captionMainHandler = Handler(Looper.getMainLooper())

/**
 * Applies Liquid Glass caption backdrop directly from a comic card's cover Bitmap,
 * computing the exact centerCrop (1:1.45) bottom region off the main thread with LRU caching
 * so RecyclerView vertical scrolling stays butter-smooth (zero main-thread blur).
 */
internal fun View.applyCoverCaptionLiquidGlass(
    coverBitmap: Bitmap?,
    cornerRadiusDp: Float = 12.5f,
    cardAspectRatio: Float = 1.45f
) {
    detachLiquidGlass()
    val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val existing = background as? LiquidGlassDrawable
    val drawable = if (existing != null) {
        existing.setDarkMode(dark)
        existing
    } else {
        LiquidGlassDrawable(
            density = resources.displayMetrics.density,
            preset = LiquidGlassPreset.CARD_CAPTION,
            cornerRadiusDp = cornerRadiusDp,
            dark = dark,
            forceDark = false
        ).also { background = it }
    }
    elevation = 3f * resources.displayMetrics.density
    if (coverBitmap == null || coverBitmap.isRecycled || coverBitmap.width < 4 || coverBitmap.height < 8) {
        setTag(R.id.tag_caption_slice_bmp, null)
        drawable.updateBackdrop(null)
        return
    }

    val cacheKey = System.identityHashCode(coverBitmap)
    setTag(R.id.tag_caption_slice_bmp, cacheKey)
    captionSliceCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { cached ->
        drawable.updateBackdrop(cached)
        return
    }

    // Offload slice extraction & CPU blur to background thread to avoid scroll jank
    captionBlurExecutor.execute {
        if (coverBitmap.isRecycled) return@execute
        val slice = runCatching {
            val bw = coverBitmap.width.toFloat()
            val bh = coverBitmap.height.toFloat()
            val targetRatio = 1f / cardAspectRatio
            val bmpRatio = bw / bh
            val cropLeft: Int
            val cropTop: Int
            val cropRight: Int
            val cropBottom: Int
            if (bmpRatio > targetRatio) {
                val visibleW = (bh * targetRatio).toInt().coerceIn(4, coverBitmap.width)
                val x0 = ((coverBitmap.width - visibleW) / 2).coerceAtLeast(0)
                cropLeft = x0
                cropRight = (x0 + visibleW).coerceAtMost(coverBitmap.width)
                cropTop = 0
                cropBottom = coverBitmap.height
            } else {
                val visibleH = (bw / targetRatio).toInt().coerceIn(8, coverBitmap.height)
                val y0 = ((coverBitmap.height - visibleH) / 2).coerceAtLeast(0)
                cropLeft = 0
                cropRight = coverBitmap.width
                cropTop = y0
                cropBottom = (y0 + visibleH).coerceAtMost(coverBitmap.height)
            }
            val visibleH = (cropBottom - cropTop).coerceAtLeast(8)
            val sliceH = (visibleH * 0.30f).toInt().coerceAtLeast(4)
            val startY = (cropBottom - sliceH).coerceAtLeast(cropTop)

            val targetW = 72
            val targetH = 28
            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(
                coverBitmap,
                Rect(cropLeft, startY, cropRight, cropBottom),
                Rect(0, 0, targetW, targetH),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            LiquidGlassBinder.blurBitmapInPlace(out, 2)
            out
        }.getOrNull() ?: return@execute
        captionSliceCache.put(cacheKey, slice)
        captionMainHandler.post {
            if (getTag(R.id.tag_caption_slice_bmp) == cacheKey && !slice.isRecycled) {
                drawable.updateBackdrop(slice)
            }
        }
    }
}
