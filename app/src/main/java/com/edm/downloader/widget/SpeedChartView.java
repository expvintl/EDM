package com.edm.downloader.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/** Simple rolling line chart of download speed. */
public class SpeedChartView extends View {

    private static final int CAPACITY = 60;

    private final float[] samples = new float[CAPACITY];
    private int count;
    private int head;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public SpeedChartView(Context context) {
        this(context, null);
    }

    public SpeedChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF1565C0);
        fillPaint.setStyle(Paint.Style.FILL);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(density);
        gridPaint.setColor(0x1A000000);
    }

    public void setColors(int line, int fillTop, int grid) {
        linePaint.setColor(line);
        fillPaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                withAlpha(fillTop, 90), withAlpha(fillTop, 0), Shader.TileMode.CLAMP));
        gridPaint.setColor(grid);
        invalidate();
    }

    public void addSample(long bytesPerSecond) {
        samples[head] = Math.max(0, bytesPerSecond);
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        invalidate();
    }

    public void clear() {
        count = 0;
        head = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float inset = 4f * getResources().getDisplayMetrics().density;
        float usableHeight = height - inset * 2;
        canvas.drawLine(inset, height - inset, width - inset, height - inset, gridPaint);

        if (count < 2) {
            return;
        }
        float max = 1;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, samples[i]);
        }
        linePath.reset();
        fillPath.reset();
        int start = (head - count + CAPACITY) % CAPACITY;
        float stepX = (width - inset * 2) / (CAPACITY - 1);
        float offsetX = width - inset - stepX * (count - 1);
        for (int i = 0; i < count; i++) {
            float value = samples[(start + i) % CAPACITY];
            float x = offsetX + stepX * i;
            float y = height - inset - usableHeight * (value / max);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, height - inset);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        fillPath.lineTo(width - inset, height - inset);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}