package com.edm.downloader.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.edm.downloader.data.TaskSegment;

import java.util.List;

/** Thin bar that shows each download segment as its own chunk of progress. */
public class SegmentBarView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private List<TaskSegment> segments;
    private long total = -1;
    private boolean complete;

    public SegmentBarView(Context context) {
        this(context, null);
    }

    public SegmentBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(0xFFE0E4EA);
        fillPaint.setColor(0xFF1565C0);
        dividerPaint.setColor(0x66FFFFFF);
        dividerPaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
    }

    public void setColors(int track, int fill) {
        trackPaint.setColor(track);
        fillPaint.setColor(fill);
        invalidate();
    }

    public void setData(List<TaskSegment> segments, long total, boolean complete) {
        this.segments = segments;
        this.total = total;
        this.complete = complete;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) (5 * getResources().getDisplayMetrics().density);
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getHeight() / 2f;
        rect.set(0, 0, getWidth(), getHeight());
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(rect, trackPaint);

        if (segments != null && !segments.isEmpty()) {
            long knownTotal = total;
            if (knownTotal <= 0) {
                long sum = 0;
                for (TaskSegment segment : segments) {
                    sum += Math.max(0, segment.length());
                }
                knownTotal = sum;
            }
            int width = getWidth();
            if (knownTotal > 0) {
                for (TaskSegment segment : segments) {
                    long length = segment.length();
                    if (length <= 0) {
                        continue;
                    }
                    float left = width * (float) segment.start / knownTotal;
                    float right = width * (float) (segment.end + 1) / knownTotal;
                    float doneFraction = (float) Math.min(1.0, (double) segment.done / length);
                    float doneRight = left + (right - left) * doneFraction;
                    if (doneRight > left) {
                        canvas.drawRect(left, 0, doneRight, getHeight(), fillPaint);
                        canvas.drawLine(right - dividerPaint.getStrokeWidth(), 0,
                                right - dividerPaint.getStrokeWidth(), getHeight(), dividerPaint);
                    }
                }
            }
        } else if (complete) {
            canvas.drawRect(rect, fillPaint);
        }
        canvas.restore();
        if (complete) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
        }
    }
}