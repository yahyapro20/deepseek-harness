package com.dshmobile.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small dependency-free readiness ring used by the provisioning screen. */
public final class DshReadinessView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value;
    private int ready;
    private int total;
    private final RectF oval = new RectF();

    public DshReadinessView(Context context) {
        super(context);
        int p = Ui.dp(context, 7);
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(p); track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Ui.border(context));
        progress.setStyle(Paint.Style.STROKE); progress.setStrokeWidth(p); progress.setStrokeCap(Paint.Cap.ROUND);
        progress.setColor(Ui.PRIMARY);
        label.setColor(Ui.text(context)); label.setTextAlign(Paint.Align.CENTER); label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setContentDescription("وضعیت آماده‌سازی");
    }

    public void setProgress(int readyCount, int totalCount) {
        ready = Math.max(0, readyCount); total = Math.max(0, totalCount);
        value = total == 0 ? 0f : Math.min(1f, ready / (float) total);
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Ui.dp(getContext(), 58);
        setMeasuredDimension(size, size);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = Ui.dp(getContext(), 7);
        oval.set(pad, pad, getWidth()-pad, getHeight()-pad);
        canvas.drawArc(oval, -90, 360, false, track);
        canvas.drawArc(oval, -90, value * 360f, false, progress);
        label.setTextSize(Ui.dp(getContext(), 13));
        canvas.drawText(ready + "/" + total, getWidth()/2f, getHeight()/2f - (label.ascent()+label.descent())/2f, label);
    }
}
