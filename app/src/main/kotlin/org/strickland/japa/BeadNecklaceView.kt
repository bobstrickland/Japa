package org.strickland.japa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a circular prayer necklace of rough wooden beads.
 * 
 * 
 * Uncounted beads are a warm light-yellow (raw wood / sandalwood).
 * Counted beads are a deep red (stained wood / rudraksha).
 * Each bead uses a RadialGradient with a per-bead jittered highlight to give
 * the hand-carved, rough-sphere appearance of real wooden mala beads.
 */
class BeadNecklaceView : View {
    private var totalBeads = 108
    private var currentBead = 0

    // Pre-allocated paints (no allocation in onDraw)
    private val cordPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beadPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cached bead geometry — rebuilt when size or total bead count changes
    private var beadX: FloatArray? = null
    private var beadY: FloatArray? = null
    private var beadRadius = 0f
    private var necklaceRadius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var geometryDirty = true

    constructor(context: Context?) : super(context) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    private fun init() {
        cordPaint.setStyle(Paint.Style.STROKE)
        cordPaint.setColor(-0xd3e5f6) // dark espresso-brown cord

        beadPaint.setStyle(Paint.Style.FILL)

        outlinePaint.setStyle(Paint.Style.STROKE)

        specPaint.setStyle(Paint.Style.FILL)
    }

    /** Update the bead state and redraw.  */
    fun setBeads(total: Int, current: Int) {
        val totalChanged = (this.totalBeads != total)
        this.totalBeads = max(1, total)
        this.currentBead = max(0, min(current, total))
        if (totalChanged) geometryDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometryDirty = true
    }

    // ── Geometry ──────────────────────────────────────────────────────────────
    private fun rebuildGeometry() {
        centerX = getWidth() / 2f
        centerY = getHeight() / 2f

        // Necklace ring takes up most of the view, leaving room for inset text
        necklaceRadius = min(getWidth(), getHeight()) / 2f * 0.86f

        if (this.totalBeads < 20) {
            necklaceRadius = necklaceRadius * 0.75f
        }

        // Fit beads tightly around the circumference (80 % fill, 20 % gap = cord)
        val circumference = 2f * Math.PI.toFloat() * necklaceRadius
        val totalBeadsForRadiusCalculation = if (totalBeads < 6) 6 else totalBeads
        beadRadius = circumference / totalBeadsForRadiusCalculation * 0.80f / 2f

        // Pre-compute bead centre positions (starting at the top, going clockwise)
        val startAngle = -Math.PI.toFloat() / 2f
        val angleStep = 2f * Math.PI.toFloat() / totalBeads
        beadX = FloatArray(totalBeads)
        beadY = FloatArray(totalBeads)
        for (i in 0..<totalBeads) {
            val a = startAngle + i * angleStep
            beadX?.set(i, centerX + necklaceRadius * cos(a.toDouble()).toFloat())
            beadY?.set(i, centerY + necklaceRadius * sin(a.toDouble()).toFloat())
        }

        cordPaint.setStrokeWidth(beadRadius * 0.35f)
        outlinePaint.setStrokeWidth(max(0.6f, beadRadius * 0.07f))
        geometryDirty = false
    }

    // ── Drawing ───────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (getWidth() == 0 || getHeight() == 0) return
        if (geometryDirty) rebuildGeometry()

        // Draw the cord first so it peeks through bead gaps
        canvas.drawCircle(centerX, centerY, necklaceRadius, cordPaint)

        for (i in 0..<totalBeads) {
            drawBead(canvas, beadX?.get(i) ?: 1.0f, beadY?.get(i) ?:  1.0f, beadRadius, i < currentBead, i)
        }
    }

    /**
     * Renders a single wooden bead using a sphere-shading RadialGradient plus a
     * small specular highlight, both offset by a deterministic per-bead jitter so
     * no two beads look identical (rough, hand-carved feel).
     */
    private fun drawBead(
        canvas: Canvas, bx: Float, by: Float, r: Float,
        counted: Boolean, seed: Int
    ) {
        // Cheap deterministic jitter — no Random object allocation

        var s = seed * 1103515245L + 12345
        val jx = (((s shr 8) and 0xFFL) / 255f - 0.5f) * 0.22f
        s = s * 1103515245L + 12345
        val jy = (((s shr 8) and 0xFFL) / 255f - 0.5f) * 0.22f

        // Highlight centre: upper-left quadrant plus per-bead jitter
        val hx = bx + r * (-0.30f + jx)
        val hy = by + r * (-0.30f + jy)

        val colors: IntArray?
        val stops = floatArrayOf(0f, 0.28f, 0.65f, 1f)

        if (counted) {
            // Stained-wood red: warm orange-red highlight → rich crimson → deep brown-red
            colors = intArrayOf(-0x6f90, -0x33d7f0, -0x74f1f8, -0xb5f9fa)
        } else {
            // Raw wood yellow: creamy highlight → warm golden tan → medium wood brown
            colors = intArrayOf(-0xb34, -0x173788, -0x4f77d0, -0x91b5f0)
        }

        // Sphere fill via radial gradient
        val sphere = RadialGradient(
            hx, hy, r * 1.7f, colors, stops, Shader.TileMode.CLAMP
        )
        beadPaint.setShader(sphere)
        canvas.drawCircle(bx, by, r, beadPaint)

        // Subtle dark ring — gives beads definition against their neighbours
        outlinePaint.setColor(if (counted) -0x66b5f7f8 else -0x669fbff0)
        canvas.drawCircle(bx, by, r - outlinePaint.getStrokeWidth() / 2f, outlinePaint)

        // Soft specular dot (diffuse — not glassy; keeps the rough-wood feel)
        specPaint.setShader(
            RadialGradient(
                hx, hy, r * 0.42f,
                intArrayOf(0x55FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        )
        canvas.drawCircle(hx, hy, r * 0.42f, specPaint)
    }
}
