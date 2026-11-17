package app.birch.debian;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import com.google.android.material.color.MaterialColors;

/** A full-width waiting wave, or a wave ending at the actual transfer fraction. */
public final class DownloadWaveView extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final ValueAnimator motion = ValueAnimator.ofFloat(0f, 1f);
    private final int primary, track;
    private final float wavelength, amplitude, sampleStep, radius;
    private float fraction, phase;
    private boolean waiting = true;
    private boolean visibleToUser;

    public DownloadWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        wavelength = 26f * density;
        amplitude = 3.5f * density;
        sampleStep = wavelength / 20f;
        radius = 1.75f * density;
        primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        track = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(2f * radius);
        motion.setDuration(1400);
        motion.setRepeatCount(ValueAnimator.INFINITE);
        motion.setInterpolator(new LinearInterpolator());
        motion.addUpdateListener(animation -> {
            phase = animation.getAnimatedFraction();
            invalidate();
        });
    }

    public void showWaiting() {
        waiting = true;
        updateMotion();
        invalidate();
    }

    public void showFraction(float fraction) {
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        waiting = false;
        updateMotion();
        invalidate();
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visibleToUser = isVisible;
        updateMotion();
    }

    @Override protected void onDetachedFromWindow() {
        visibleToUser = false;
        updateMotion();
        super.onDetachedFromWindow();
    }

    private void updateMotion() {
        if (visibleToUser && (waiting || (fraction > 0f && fraction < 1f))
                && ValueAnimator.areAnimatorsEnabled()) {
            if (!motion.isStarted()) motion.start();
        } else if (motion.isStarted()) {
            motion.cancel();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - 2f * radius;
        if (width <= 0f) return;
        int save = canvas.save();
        canvas.translate(radius, getHeight() * 0.5f);
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) {
            canvas.translate(width, 0f);
            canvas.scale(-1f, 1f);
        }
        float filled = fraction * width;
        float height = amplitude;
        if (waiting) {
            filled = width;
            height *= 0.55f;
        } else if (filled < width) {
            float start = 0f;
            if (filled > 0f) start = filled + 4f * radius;
            if (start < width) {
                stroke.setColor(track);
                canvas.drawLine(start, 0f, width, 0f, stroke);
            }
        }
        if (filled > 0f) {
            wave.rewind();
            wave.moveTo(0f, 0f);
            for (float x = sampleStep; x < filled; x += sampleStep) {
                // Taper both ends so the moving tip never overshoots its byte position.
                float envelope = Math.min(1f, Math.min(x, filled - x) / (wavelength * 0.4f));
                float y = height * envelope * (float) Math.sin(2.0 * Math.PI * (x / wavelength - phase));
                wave.lineTo(x, y);
            }
            wave.lineTo(filled, 0f);
            stroke.setColor(primary);
            canvas.drawPath(wave, stroke);
        }
        canvas.restoreToCount(save);
    }
}
