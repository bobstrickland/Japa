package org.strickland.japa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Draws a circular prayer necklace of rough wooden beads.
 * <p>
 * Uncounted beads are a warm light-yellow (raw wood / sandalwood).
 * Counted beads are a deep red (stained wood / rudraksha).
 * Each bead uses a RadialGradient with a per-bead jittered highlight to give
 * the hand-carved, rough-sphere appearance of real wooden mala beads.
 */
public class BeadNecklaceView extends View {

    private int totalBeads  = 108;
    private int currentBead = 0;

    // Pre-allocated paints (no allocation in onDraw)
    private final Paint cordPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beadPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Cached bead geometry — rebuilt when size or total bead count changes
    private float[] beadX;
    private float[] beadY;
    private float   beadRadius;
    private float   necklaceRadius;
    private float   centerX, centerY;
    private boolean geometryDirty = true;

    public BeadNecklaceView(Context context) {
        super(context);
        init();
    }

    public BeadNecklaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BeadNecklaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        cordPaint.setStyle(Paint.Style.STROKE);
        cordPaint.setColor(0xFF2C1A0A); // dark espresso-brown cord

        beadPaint.setStyle(Paint.Style.FILL);

        outlinePaint.setStyle(Paint.Style.STROKE);

        specPaint.setStyle(Paint.Style.FILL);
    }

    /** Update the bead state and redraw. */
    public void setBeads(int total, int current) {
        boolean totalChanged = (this.totalBeads != total);
        this.totalBeads  = Math.max(1, total);
        this.currentBead = Math.max(0, Math.min(current, total));
        if (totalChanged) geometryDirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometryDirty = true;
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private void rebuildGeometry() {
        centerX = getWidth()  / 2f;
        centerY = getHeight() / 2f;

        // Necklace ring takes up most of the view, leaving room for inset text
        necklaceRadius = Math.min(getWidth(), getHeight()) / 2f * 0.86f;

        if (this.totalBeads < 20){
            necklaceRadius = necklaceRadius * 0.75f;
        }

        // Fit beads tightly around the circumference (80 % fill, 20 % gap = cord)
        float circumference = 2f * (float) Math.PI * necklaceRadius;
        int totalBeadsForRadiusCalculation = totalBeads < 6?6:totalBeads;
        beadRadius = circumference / totalBeadsForRadiusCalculation * 0.80f / 2f;

        // Pre-compute bead centre positions (starting at the top, going clockwise)
        float startAngle = -(float) Math.PI / 2f;
        float angleStep  = 2f * (float) Math.PI / totalBeads;
        beadX = new float[totalBeads];
        beadY = new float[totalBeads];
        for (int i = 0; i < totalBeads; i++) {
            float a = startAngle + i * angleStep;
            beadX[i] = centerX + necklaceRadius * (float) Math.cos(a);
            beadY[i] = centerY + necklaceRadius * (float) Math.sin(a);
        }

        cordPaint.setStrokeWidth(beadRadius * 0.35f);
        outlinePaint.setStrokeWidth(Math.max(0.6f, beadRadius * 0.07f));
        geometryDirty = false;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        if (geometryDirty) rebuildGeometry();

        // Draw the cord first so it peeks through bead gaps
        canvas.drawCircle(centerX, centerY, necklaceRadius, cordPaint);

        for (int i = 0; i < totalBeads; i++) {
            drawBead(canvas, beadX[i], beadY[i], beadRadius, i < currentBead, i);
        }
    }

    /**
     * Renders a single wooden bead using a sphere-shading RadialGradient plus a
     * small specular highlight, both offset by a deterministic per-bead jitter so
     * no two beads look identical (rough, hand-carved feel).
     */
    private void drawBead(Canvas canvas, float bx, float by, float r,
                           boolean counted, int seed) {

        // Cheap deterministic jitter — no Random object allocation
        long s = seed * 1103515245L + 12345;
        float jx = (((s >> 8) & 0xFFL) / 255f - 0.5f) * 0.22f;
        s = s * 1103515245L + 12345;
        float jy = (((s >> 8) & 0xFFL) / 255f - 0.5f) * 0.22f;

        // Highlight centre: upper-left quadrant plus per-bead jitter
        float hx = bx + r * (-0.30f + jx);
        float hy = by + r * (-0.30f + jy);

        int[] colors;
        float[] stops = { 0f, 0.28f, 0.65f, 1f };

        if (counted) {
            // Stained-wood red: warm orange-red highlight → rich crimson → deep brown-red
            colors = new int[]{ 0xFFFF9070, 0xFFCC2810, 0xFF8B0E08, 0xFF4A0606 };
        } else {
            // Raw wood yellow: creamy highlight → warm golden tan → medium wood brown
            colors = new int[]{ 0xFFFFF4CC, 0xFFE8C878, 0xFFB08830, 0xFF6E4A10 };
        }

        // Sphere fill via radial gradient
        RadialGradient sphere = new RadialGradient(
                hx, hy, r * 1.7f, colors, stops, Shader.TileMode.CLAMP);
        beadPaint.setShader(sphere);
        canvas.drawCircle(bx, by, r, beadPaint);

        // Subtle dark ring — gives beads definition against their neighbours
        outlinePaint.setColor(counted ? 0x994A0808 : 0x99604010);
        canvas.drawCircle(bx, by, r - outlinePaint.getStrokeWidth() / 2f, outlinePaint);

        // Soft specular dot (diffuse — not glassy; keeps the rough-wood feel)
        specPaint.setShader(new RadialGradient(
                hx, hy, r * 0.42f,
                new int[]{ 0x55FFFFFF, 0x00FFFFFF },
                new float[]{ 0f, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawCircle(hx, hy, r * 0.42f, specPaint);
    }
}
