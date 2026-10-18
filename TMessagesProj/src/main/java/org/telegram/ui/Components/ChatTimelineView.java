package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.Calendar;

/**
 * Chronologie — a timeline scrubber for the chat history.
 *
 * Lives on the trailing edge of the chat list, on top of it. A round handle
 * appears while the user scrolls, aligned with the date currently shown at the
 * top of the chat. Grabbing the handle and dragging it scrubs through the whole
 * history: a bubble next to the finger shows the month under it, and releasing
 * jumps the chat to that date (through ChatActivity.jumpToDate). A tap without
 * movement opens the existing calendar.
 *
 * The view consumes touches only on the handle, so the ordinary scrolling of the
 * chat is untouched.
 *
 * Lot 1: handle, show/hide cycle, drag with a provisional mapping that is linear
 * in time between a fixed origin and now, bubble with the month, jump, tap.
 * Lot 2 replaces the mapping with ChatTimelineIndex (content-proportional,
 * exact per month) and adds the unfolding ruler of years and months.
 */
public class ChatTimelineView extends View {

    public interface Delegate {
        /** Called on release after a drag; date is in unix seconds. */
        void onJumpToDate(int date);
        /** Called on a tap without movement; date is the date currently shown by the chat. */
        void onOpenCalendar(int date);
        /** Whether the handle may be shown right now (false during selection mode, search, etc). */
        boolean canShow();
    }

    /** Provisional origin of the mapping (14 August 2013, Telegram's first release). Lot 2 uses the real first message. */
    private static final int ORIGIN_DATE = 1376438400;

    private static final long HIDE_DELAY_MS = 1800;
    private static final float HANDLE_RADIUS_DP = 22f;
    private static final float HANDLE_EDGE_INSET_DP = 7f; // how far the center sits from the edge; the disc is cut by the edge, like shared media
    private static final float VERTICAL_MARGIN_DP = 8f;

    private final Theme.ResourcesProvider resourcesProvider;
    private Delegate delegate;

    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevronPath = new Path();
    private final RectF bubbleRect = new RectF();
    private final RectF hitRect = new RectF();
    private final Drawable shadowDrawable;

    private final AnimatedFloat showProgress;
    private final AnimatedFloat dragProgress;

    private final boolean rtl;
    private final float touchSlop;

    private boolean allowed;
    private boolean shown;
    private boolean dragging;
    private boolean moved;

    private float boundsTop;
    private float boundsBottom;

    /** 0 = oldest message (top of the ruler), 1 = newest (bottom), like the chat itself. */
    private float progress = 1f;
    private int currentDate;

    private float downY;
    private float startProgress;

    private int bubbleDate;
    private int bubbleMonthKey = -1;
    private String bubbleText;
    private float bubbleTextWidth;

    private final Calendar calendar = Calendar.getInstance();

    private final Runnable hideRunnable = () -> {
        if (dragging) {
            scheduleHide(HIDE_DELAY_MS);
            return;
        }
        shown = false;
        invalidate();
    };

    public ChatTimelineView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rtl = LocaleController.isRTL;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        showProgress = new AnimatedFloat(this, 180, CubicBezierInterpolator.EASE_OUT_QUINT);
        dragProgress = new AnimatedFloat(this, 220, CubicBezierInterpolator.EASE_OUT_BACK);

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(AndroidUtilities.dp(2));
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);

        bubbleTextPaint.setTextSize(AndroidUtilities.dp(14));
        bubbleTextPaint.setTypeface(AndroidUtilities.bold());

        shadowDrawable = ContextCompat.getDrawable(context, R.drawable.fast_scroll_shadow);

        updateColors();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        if (!allowed) {
            shown = false;
            dragging = false;
            invalidate();
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    /** The chat scrolled under the finger (or is flinging): show the handle. */
    public void onChatScrolled() {
        if (!allowed || (delegate != null && !delegate.canShow())) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        if (!shown) {
            shown = true;
            invalidate();
        }
    }

    /** The chat stopped scrolling: hide the handle after a short delay. */
    public void onChatScrollIdle() {
        scheduleHide(HIDE_DELAY_MS);
    }

    public void hideNow() {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        if (shown && !dragging) {
            shown = false;
            invalidate();
        }
    }

    /** Date (unix seconds) of the message currently shown at the top of the chat. */
    public void setCurrentDate(int date) {
        if (date <= 0 || date == currentDate) {
            return;
        }
        currentDate = date;
        if (!dragging) {
            progress = progressForDate(date);
            if (shown) {
                invalidate();
            }
        }
    }

    /** Vertical span of the chat area, in this view's coordinates (below the top panels, above the composer). */
    public void setBounds(float top, float bottom) {
        if (Math.abs(top - boundsTop) < 0.5f && Math.abs(bottom - boundsBottom) < 0.5f) {
            return;
        }
        boundsTop = top;
        boundsBottom = bottom;
        if (shown || dragging) {
            invalidate();
        }
    }

    public void updateColors() {
        int background = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        handlePaint.setColor(ColorUtils.blendARGB(background, Color.WHITE, 0.1f));
        chevronPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        bubblePaint.setColor(Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider));
        bubbleTextPaint.setColor(Theme.getColor(Theme.key_chat_serviceText, resourcesProvider));
        invalidate();
    }

    public void destroy() {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        dragging = false;
        shown = false;
    }

    private void scheduleHide(long delay) {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        AndroidUtilities.runOnUIThread(hideRunnable, delay);
    }

    // ---- mapping (provisional, lot 1) ----

    private int nowDate() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    private float progressForDate(int date) {
        int now = nowDate();
        if (date <= ORIGIN_DATE) {
            return 0f;
        }
        if (date >= now) {
            return 1f;
        }
        return (date - ORIGIN_DATE) / (float) (now - ORIGIN_DATE);
    }

    private int dateForProgress(float p) {
        int now = nowDate();
        return ORIGIN_DATE + (int) ((now - ORIGIN_DATE) * clamp01(p));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ---- geometry ----

    private float handleRadius() {
        return AndroidUtilities.dp(HANDLE_RADIUS_DP);
    }

    private float handleCenterX() {
        return rtl ? AndroidUtilities.dp(HANDLE_EDGE_INSET_DP) : getMeasuredWidth() - AndroidUtilities.dp(HANDLE_EDGE_INSET_DP);
    }

    private float travelTop() {
        return boundsTop + AndroidUtilities.dp(VERTICAL_MARGIN_DP) + handleRadius();
    }

    private float travelBottom() {
        float bottom = boundsBottom > 0 ? boundsBottom : getMeasuredHeight();
        return bottom - AndroidUtilities.dp(VERTICAL_MARGIN_DP) - handleRadius();
    }

    private float handleCenterY() {
        float top = travelTop();
        float bottom = travelBottom();
        if (bottom <= top) {
            return top;
        }
        return top + (bottom - top) * clamp01(progress);
    }

    private float progressForY(float y) {
        float top = travelTop();
        float bottom = travelBottom();
        if (bottom <= top) {
            return 1f;
        }
        return clamp01((y - top) / (bottom - top));
    }

    // ---- bubble ----

    private void updateBubble(int date, boolean haptic) {
        calendar.setTimeInMillis(date * 1000L);
        int key = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH);
        if (key == bubbleMonthKey) {
            bubbleDate = date;
            return;
        }
        boolean first = bubbleMonthKey == -1;
        bubbleMonthKey = key;
        bubbleDate = date;
        bubbleText = LocaleController.formatYearMont(date, true);
        bubbleTextWidth = bubbleTextPaint.measureText(bubbleText);
        if (haptic && !first) {
            try {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
        }
        invalidate();
    }

    // ---- touch ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!allowed) {
            return false;
        }
        final int action = event.getActionMasked();
        final float x = event.getX();
        final float y = event.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (!shown) {
                    return false;
                }
                float cx = handleCenterX();
                float cy = handleCenterY();
                float r = handleRadius() + AndroidUtilities.dp(8);
                hitRect.set(cx - r, cy - r, cx + r, cy + r);
                if (!hitRect.contains(x, y)) {
                    return false;
                }
                dragging = true;
                moved = false;
                downY = y;
                startProgress = progress;
                AndroidUtilities.cancelRunOnUIThread(hideRunnable);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                try {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {}
                bubbleMonthKey = -1;
                updateBubble(currentDate > 0 ? currentDate : dateForProgress(progress), false);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    return false;
                }
                float dy = y - downY;
                if (!moved && Math.abs(dy) > touchSlop) {
                    moved = true;
                }
                if (moved) {
                    float top = travelTop();
                    float bottom = travelBottom();
                    float travel = Math.max(1f, bottom - top);
                    progress = clamp01(startProgress + dy / travel);
                    updateBubble(dateForProgress(progress), true);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!dragging) {
                    return false;
                }
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (delegate != null) {
                    if (moved) {
                        delegate.onJumpToDate(dateForProgress(progress));
                    } else {
                        delegate.onOpenCalendar(currentDate > 0 ? currentDate : nowDate());
                    }
                }
                scheduleHide(HIDE_DELAY_MS);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    return false;
                }
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (currentDate > 0) {
                    progress = progressForDate(currentDate);
                }
                scheduleHide(HIDE_DELAY_MS);
                invalidate();
                return true;
            }
        }
        return false;
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        float visible = showProgress.set(shown || dragging ? 1f : 0f);
        if (visible <= 0f) {
            return;
        }
        float cx = handleCenterX();
        float cy = handleCenterY();
        float r = handleRadius();
        int alpha = (int) (255 * visible);

        // shadow hugging the edge, like the shared media scroller
        if (shadowDrawable != null) {
            int w = shadowDrawable.getIntrinsicWidth();
            int h = shadowDrawable.getIntrinsicHeight();
            int left = rtl ? 0 : getMeasuredWidth() - w;
            shadowDrawable.setBounds(left, (int) (cy - h / 2f), left + w, (int) (cy + h / 2f));
            shadowDrawable.setAlpha(alpha);
            if (rtl) {
                canvas.save();
                canvas.scale(-1f, 1f, w / 2f, cy);
                shadowDrawable.draw(canvas);
                canvas.restore();
            } else {
                shadowDrawable.draw(canvas);
            }
        }

        // the disc
        handlePaint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, r, handlePaint);

        // two chevrons: up and down, spreading slightly while dragging
        float drag = dragProgress.set(dragging ? 1f : 0f);
        chevronPaint.setAlpha(alpha);
        float chevronX = rtl ? cx + AndroidUtilities.dp(4) : cx - AndroidUtilities.dp(4);
        float half = AndroidUtilities.dp(4);
        float gap = AndroidUtilities.dp(5) + AndroidUtilities.dp(2) * drag;
        chevronPath.rewind();
        chevronPath.moveTo(chevronX - half, cy - gap);
        chevronPath.lineTo(chevronX, cy - gap - half);
        chevronPath.lineTo(chevronX + half, cy - gap);
        canvas.drawPath(chevronPath, chevronPaint);
        chevronPath.rewind();
        chevronPath.moveTo(chevronX - half, cy + gap);
        chevronPath.lineTo(chevronX, cy + gap + half);
        chevronPath.lineTo(chevronX + half, cy + gap);
        canvas.drawPath(chevronPath, chevronPaint);

        // the bubble with the month under the finger
        if (drag > 0f && bubbleText != null) {
            float padH = AndroidUtilities.dp(12);
            float padV = AndroidUtilities.dp(7);
            float textH = bubbleTextPaint.descent() - bubbleTextPaint.ascent();
            float width = bubbleTextWidth + padH * 2;
            float height = textH + padV * 2;
            float gapToHandle = AndroidUtilities.dp(10);
            float left = rtl ? cx + r + gapToHandle : cx - r - gapToHandle - width;
            bubbleRect.set(left, cy - height / 2f, left + width, cy + height / 2f);

            canvas.save();
            float s = 0.7f + 0.3f * drag;
            canvas.scale(s, s, rtl ? bubbleRect.left : bubbleRect.right, cy);
            bubblePaint.setAlpha((int) (255 * drag * visible));
            canvas.drawRoundRect(bubbleRect, height / 2f, height / 2f, bubblePaint);
            bubbleTextPaint.setAlpha((int) (255 * drag * visible));
            canvas.drawText(bubbleText, bubbleRect.left + padH, cy - (bubbleTextPaint.descent() + bubbleTextPaint.ascent()) / 2f, bubbleTextPaint);
            canvas.restore();
        }
    }
}
