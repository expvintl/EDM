package com.edm.downloader.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Circular progress ring with percentage text; used in list rows. */
public class CircularProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private int percent;
    private boolean indeterminate;
    private boolean done;
    private float rotation;

    public CircularProgressView(Context context) {
        this(context, null);
    }

    public CircularProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        float stroke = 4f * density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        trackPaint.setColor(0xFFE0E4EA);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(0xFF1565C0);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(11.5f * density);
        textPaint.setColor(0xFF1565C0);
    }

    public void setColors(int track, int progress, int text) {
        trackPaint.setColor(track);
        progressPaint.setColor(progress);
        textPaint.setColor(text);
        invalidate();
    }

    public void setProgress(int percent, boolean indeterminate, boolean done) {
        this.percent = Math.max(0, Math.min(100, percent));
        this.indeterminate = indeterminate;
        this.done = done;
        if (indeterminate) {
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (44 * getResources().getDisplayMetrics().density);
        int width = resolveSize(size, widthMeasureSpec);
        int height = resolveSize(size, heightMeasureSpec);
        int min = Math.min(width, height);
        setMeasuredDimension(min, min);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        progressPaint.setStyle(Paint.Style.STROKE);
        textPaint.setTextSize(11.5f * getResources().getDisplayMetrics().density);
        float stroke = trackPaint.getStrokeWidth();
        float inset = stroke / 2f + 1f;
        arcRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(arcRect, 0, 360, false, trackPaint);

        if (indeterminate) {
            rotation += 6f;
            canvas.drawArc(arcRect, rotation, 80, false, progressPaint);
            if (isAttachedToWindow()) {
                postInvalidateOnAnimation();
            }
            return;
        }
        if (done) {
            progressPaint.setStyle(Paint.Style.FILL);
            textPaint.setTextSize(20f * getResources().getDisplayMetrics().density);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText("✓", cx, cy, textPaint);
            return;
        }
        progressPaint.setStyle(Paint.Style.STROKE);
        float sweep = 360f * percent / 100f;
        canvas.drawArc(arcRect, -90, sweep, false, progressPaint);

        String label = percent + "%";
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(label, cx, cy, textPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (indeterminate) {
            postInvalidateOnAnimation();
        }
    }
}