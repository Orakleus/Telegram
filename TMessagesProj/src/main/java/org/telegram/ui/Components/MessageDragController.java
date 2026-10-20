package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.ArrayList;

/**
 * "Drag to forward". A long press selects a message, as it always did on Android. Pulling the
 * bubble sideways while the finger is still down detaches a copy of it that follows the finger
 * (the pressed message alone, or the whole selection it belongs to), while a shelf of recent
 * chats ({@link DropShelfView}) rises from the bottom. Dropping the copy on a chat forwards the
 * messages through the delegate, which uses the same send path and undo toast as Quick Share;
 * releasing anywhere else springs the copy back to its place.
 *
 * The controller adds two views to the chat's content view, in this order: the shelf, then an
 * overlay that dims the chat above the shelf and carries the lifted bubble as its only child.
 * It sees every touch event first, through ChatActivityFragmentView.dispatchTouchEvent: while
 * armed it only watches, once dragging it consumes everything.
 */
public class MessageDragController {

    public interface Delegate {
        /** Whether this cell may be dragged at all (chat mode, chat type, message type). */
        boolean canDrag(ChatMessageCell cell);
        /** The messages a drag of this cell carries: the selection it belongs to, its album, or itself. */
        ArrayList<MessageObject> messagesToDrag(ChatMessageCell cell);
        /** Forwards the messages to the dialog and shows the forwarded toast with undo. */
        void forward(ArrayList<MessageObject> messages, long dialogId);
        /** Bottom system inset the shelf must stay above. */
        int getBottomInset();
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_ARMED = 1;    // long press done, finger still down on a draggable message
    private static final int STATE_DRAGGING = 2;
    private static final int STATE_FLYING = 3;   // the copy flies into the avatar or back home

    private static final long ARMED_SAFETY_TIMEOUT_MS = 10_000;
    private static final float LIFTED_SCALE = 0.6f;
    private static final float DROPPED_SCALE = 0.1f;
    private static final int CHIP_MAX_WIDTH = 120;   // dp, size of the copy while over the shelf
    private static final int CHIP_MAX_HEIGHT = 80;
    private static final int CHIP_LIFT = 24;         // dp between the finger and the chip's bottom
    private static final int SNAPSHOT_MARGIN = 8;    // dp around the bubble, for its shadow
    private static final float DIM_ALPHA = 0.2f;     // same as the context menu's scrim

    private final FrameLayout contentView;
    private final RecyclerListView listView;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final Overlay overlay;
    private final LiftedBubble bubble;
    private final DropShelfView shelf;

    private int state = STATE_IDLE;
    private boolean allowed = true;
    private boolean pointerDown;
    private float lastX, lastY;        // last finger position, content-view coordinates
    private float armX, armY;          // where the sideways pull is measured from
    private ChatMessageCell armedCell;
    private ArrayList<MessageObject> dragged;

    // geometry, in overlay coordinates
    private final RectF origin = new RectF();   // where the copy was taken from
    private float grabX, grabY;                  // grab point inside the unscaled copy
    private float fingerX, fingerY;
    private final float[] tmpPoint = new float[2];

    private float dimTarget, scaleTarget, chipTarget;
    private final AnimatedFloat dimT;
    private final AnimatedFloat scaleT;
    private final AnimatedFloat chipT;

    private ValueAnimator flight;
    private float flightProgress;
    private float flightScale0, flightScale1, flightLeft0, flightLeft1, flightTop0, flightTop1, flightAlpha0, flightAlpha1;

    private final Runnable disarmRunnable = this::disarm;

    public MessageDragController(Context context, FrameLayout contentView, RecyclerListView listView, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        this.contentView = contentView;
        this.listView = listView;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;

        shelf = new DropShelfView(context, resourcesProvider);
        contentView.addView(shelf, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 230, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL));

        overlay = new Overlay(context);
        bubble = new LiftedBubble(context);
        overlay.addView(bubble, new FrameLayout.LayoutParams(1, 1, Gravity.LEFT | Gravity.TOP));
        contentView.addView(overlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

        dimT = new AnimatedFloat(overlay, 150, CubicBezierInterpolator.EASE_OUT);
        scaleT = new AnimatedFloat(overlay, 180, CubicBezierInterpolator.EASE_OUT_QUINT);
        chipT = new AnimatedFloat(overlay, 160, CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
        if (!allowed && state != STATE_IDLE) {
            cancelDrag();
        }
    }

    public boolean isDragging() {
        return state == STATE_DRAGGING || state == STATE_FLYING;
    }

    /**
     * Called by the chat when a long press selected a message. The finger is still down: from now
     * on a sideways pull starts the drag; lifting the finger, or sweeping up and down to select
     * more (the list's own gesture), leaves everything as it is.
     */
    public void armFromLongPress(View pressedView) {
        if (!allowed || !pointerDown || state != STATE_IDLE || !(pressedView instanceof ChatMessageCell)) {
            return;
        }
        final ChatMessageCell cell = (ChatMessageCell) pressedView;
        if (!delegate.canDrag(cell)) {
            return;
        }
        armedCell = cell;
        armX = lastX;
        armY = lastY;
        state = STATE_ARMED;
        AndroidUtilities.cancelRunOnUIThread(disarmRunnable);
        AndroidUtilities.runOnUIThread(disarmRunnable, ARMED_SAFETY_TIMEOUT_MS);
    }

    /**
     * Sees every touch event of the chat's content view before anything else. Returns true when
     * the event belongs to the drag and must not reach any child.
     */
    public boolean onDispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        final float x = ev.getX();
        final float y = ev.getY();
        switch (state) {
            case STATE_IDLE:
                if (action == MotionEvent.ACTION_DOWN) {
                    pointerDown = true;
                    lastX = x;
                    lastY = y;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    lastX = x;
                    lastY = y;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pointerDown = false;
                }
                return false;
            case STATE_ARMED:
                if (action == MotionEvent.ACTION_MOVE) {
                    lastX = x;
                    lastY = y;
                    final float dx = x - armX;
                    final float dy = y - armY;
                    // the threshold of the swipe-to-reply and of the back swipe
                    if (Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.4f, true) && Math.abs(dx) / 3f > Math.abs(dy)) {
                        startDrag(x, y);
                        return true;
                    }
                    if (Math.abs(dy) > Math.abs(dx)) {
                        // the finger sweeps up or down (the list selects what it crosses): the pull is measured from here
                        armX = x;
                        armY = y;
                    }
                    return false;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pointerDown = false;
                    disarm();
                } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    disarm();
                }
                return false;
            case STATE_DRAGGING:
                if (action == MotionEvent.ACTION_MOVE) {
                    moveTo(x, y);
                } else if (action == MotionEvent.ACTION_UP) {
                    pointerDown = false;
                    release();
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    pointerDown = false;
                    cancelDrag();
                }
                return true;
            case STATE_FLYING:
            default:
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pointerDown = false;
                }
                return true;
        }
    }

    private void disarm() {
        AndroidUtilities.cancelRunOnUIThread(disarmRunnable);
        if (state == STATE_ARMED) {
            state = STATE_IDLE;
        }
        armedCell = null;
    }

    private void startDrag(float x, float y) {
        AndroidUtilities.cancelRunOnUIThread(disarmRunnable);
        final ChatMessageCell cell = armedCell;
        armedCell = null;
        dragged = cell == null ? null : delegate.messagesToDrag(cell);
        if (dragged == null || dragged.isEmpty() || !bubble.takeSnapshot(cell)) {
            state = STATE_IDLE;
            return;
        }
        state = STATE_DRAGGING;

        // the list has an open gesture (its sweep-to-select after a long press): close it
        final long now = SystemClock.uptimeMillis();
        final MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        listView.dispatchTouchEvent(cancel);
        cancel.recycle();
        bubble.setCount(dragged.size());

        // where the copy comes from, and where the finger holds it
        origin.set(
            listView.getX() + cell.getX() + bubble.cellLeft - overlay.getLeft(),
            listView.getY() + cell.getY() + bubble.cellTop - overlay.getTop(),
            0, 0
        );
        origin.right = origin.left + bubble.getSnapshotWidth();
        origin.bottom = origin.top + bubble.getSnapshotHeight();
        fingerX = x - overlay.getLeft();
        fingerY = y - overlay.getTop();
        grabX = Math.max(0, Math.min(bubble.getSnapshotWidth(), fingerX - origin.left));
        grabY = Math.max(0, Math.min(bubble.getSnapshotHeight(), fingerY - origin.top));

        dimT.set(0, true);
        scaleT.set(0, true);
        chipT.set(0, true);
        dimTarget = 1;
        scaleTarget = 1;
        chipTarget = 0;
        bubble.setAlpha(1f);
        bubble.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
        overlay.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        shelf.show(delegate.getBottomInset());
        moveTo(x, y);
    }

    private void moveTo(float x, float y) {
        lastX = x;
        lastY = y;
        fingerX = x - overlay.getLeft();
        fingerY = y - overlay.getTop();
        final boolean overShelf = shelf.isRisen() && y >= shelf.getY();
        shelf.setHovered(overShelf ? shelf.dialogAt(x - shelf.getX(), y - shelf.getY()) : 0);
        chipTarget = overShelf ? 1 : 0;
        overlay.invalidate();
    }

    private void release() {
        final long dialogId = shelf.getHoveredDialogId();
        if (dialogId != 0 && shelf.avatarCenter(dialogId, tmpPoint)) {
            final ArrayList<MessageObject> messages = dragged;
            final float cx = shelf.getX() + tmpPoint[0] - overlay.getLeft();
            final float cy = shelf.getY() + tmpPoint[1] - overlay.getTop();
            fly(cx - bubble.getSnapshotWidth() * DROPPED_SCALE / 2f, cy - bubble.getSnapshotHeight() * DROPPED_SCALE / 2f, DROPPED_SCALE, 0f, 220, CubicBezierInterpolator.EASE_IN);
            delegate.forward(messages, dialogId);
        } else {
            flyBack();
        }
        shelf.hide();
        dimTarget = 0;
        overlay.invalidate();
    }

    private void cancelDrag() {
        if (state != STATE_DRAGGING) {
            if (state == STATE_ARMED) {
                disarm();
            }
            return;
        }
        flyBack();
        shelf.hide();
        dimTarget = 0;
        overlay.invalidate();
    }

    private void flyBack() {
        fly(origin.left, origin.top, 1f, 1f, 260, CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    private void fly(float left, float top, float scale, float alpha, long duration, android.animation.TimeInterpolator interpolator) {
        state = STATE_FLYING;
        flightScale0 = bubble.getScaleX();
        flightLeft0 = bubble.getTranslationX();
        flightTop0 = bubble.getTranslationY();
        flightAlpha0 = bubble.getAlpha();
        flightScale1 = scale;
        flightLeft1 = left;
        flightTop1 = top;
        flightAlpha1 = alpha;
        flightProgress = 0;
        if (flight != null) {
            flight.cancel();
        }
        flight = ValueAnimator.ofFloat(0f, 1f);
        flight.setDuration(duration);
        flight.setInterpolator(interpolator);
        flight.addUpdateListener(a -> {
            flightProgress = (float) a.getAnimatedValue();
            overlay.invalidate();
        });
        flight.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (flight == animation) {
                    flight = null;
                    finishFlight();
                }
            }
        });
        flight.start();
    }

    private void finishFlight() {
        bubble.setVisibility(View.GONE);
        bubble.recycle();
        dragged = null;
        state = STATE_IDLE;
        overlay.invalidate();
    }

    /** Positions the copy for this frame: following the finger, shrinking over the shelf, or flying. */
    private void layoutBubble() {
        float scale, left, top, alpha;
        if (state == STATE_FLYING) {
            final float p = flightProgress;
            scale = lerp(flightScale0, flightScale1, p);
            left = lerp(flightLeft0, flightLeft1, p);
            top = lerp(flightTop0, flightTop1, p);
            alpha = lerp(flightAlpha0, flightAlpha1, p);
        } else {
            final float t = scaleT.set(scaleTarget);
            final float c = chipT.set(chipTarget);
            final float w = bubble.getSnapshotWidth();
            final float h = bubble.getSnapshotHeight();
            final float liftedScale = lerp(1f, LIFTED_SCALE, t);
            final float liftedLeft = fingerX - grabX * liftedScale;
            final float liftedTop = fingerY - grabY * liftedScale;
            final float chipScale = Math.min(LIFTED_SCALE, Math.min(dp(CHIP_MAX_WIDTH) / w, dp(CHIP_MAX_HEIGHT) / h));
            final float chipLeft = fingerX - w * chipScale / 2f;
            final float chipTop = fingerY - dp(CHIP_LIFT) - h * chipScale;
            scale = lerp(liftedScale, chipScale, c);
            left = lerp(liftedLeft, chipLeft, c);
            top = lerp(liftedTop, chipTop, c);
            alpha = 1f;
        }
        bubble.setScaleX(scale);
        bubble.setScaleY(scale);
        bubble.setTranslationX(left);
        bubble.setTranslationY(top);
        bubble.setAlpha(alpha);
    }

    public void destroy() {
        AndroidUtilities.cancelRunOnUIThread(disarmRunnable);
        if (flight != null) {
            flight.cancel();
            flight = null;
        }
        shelf.animate().cancel();
        bubble.recycle();
        contentView.removeView(overlay);
        contentView.removeView(shelf);
        state = STATE_IDLE;
    }

    /** Dims the chat above the shelf and carries the lifted bubble; invisible when idle. */
    private class Overlay extends FrameLayout {

        private final Paint dimPaint = new Paint();

        Overlay(Context context) {
            super(context);
            setWillNotDraw(false);
            setVisibility(INVISIBLE); // laid out while idle, so its offset is known at the first drag
            setClipChildren(false);
            dimPaint.setColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float dim = dimT.set(dimTarget);
            if (dim > 0) {
                dimPaint.setAlpha((int) (0xFF * DIM_ALPHA * dim));
                final float bottom = shelf.isRisen() ? shelf.getY() - getTop() : getMeasuredHeight();
                canvas.drawRect(0, 0, getMeasuredWidth(), bottom, dimPaint);
                if (shelf.isRisen()) {
                    invalidate(); // the shelf is moving
                }
            }
            if (bubble.getVisibility() == VISIBLE) {
                layoutBubble();
            } else if (state == STATE_IDLE && dim <= 0 && !dimT.isInProgress()) {
                post(() -> {
                    if (state == STATE_IDLE) {
                        setVisibility(INVISIBLE);
                    }
                });
            }
            super.onDraw(canvas);
        }
    }

    /** The copy of the bubble that follows the finger: a bitmap of the cell, with a shadow. */
    private class LiftedBubble extends View {

        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint badgeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap snapshot;
        private int snapshotWidth, snapshotHeight;
        int cellLeft, cellTop;   // top-left of the snapshot inside the cell
        private String countText;      // the badge of a stack: how many messages travel, null for one
        private float countTextWidth;

        LiftedBubble(Context context) {
            super(context);
            setVisibility(GONE);
            setPivotX(0);
            setPivotY(0);
            setElevation(dp(12));
            badgeTextPaint.setTypeface(AndroidUtilities.bold());
            badgeTextPaint.setTextSize(dp(13));
            badgeTextPaint.setColor(Color.WHITE);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    final int m = dp(SNAPSHOT_MARGIN);
                    outline.setRoundRect(m, m, view.getMeasuredWidth() - m, view.getMeasuredHeight() - m, dp(12));
                }
            });
        }

        /** The number of messages travelling; above one, a badge is drawn on the copy. */
        void setCount(int count) {
            if (count > 1) {
                countText = String.valueOf(count);
                countTextWidth = badgeTextPaint.measureText(countText);
                badgePaint.setColor(Theme.getColor(Theme.key_chat_goDownButtonCounterBackground, resourcesProvider));
            } else {
                countText = null;
            }
            invalidate();
        }

        int getSnapshotWidth() {
            return snapshotWidth;
        }

        int getSnapshotHeight() {
            return snapshotHeight;
        }

        /**
         * Draws the cell into a bitmap the size of its bubble (the one allocation of a drag), the
         * way the chat draws the pressed cell above its scrim. False if nothing could be drawn.
         */
        boolean takeSnapshot(ChatMessageCell cell) {
            recycle();
            if (cell == null || cell.getMeasuredWidth() <= 0 || cell.getMeasuredHeight() <= 0) {
                return false;
            }
            int left = cell.getBackgroundDrawableLeft();
            int top = cell.getBackgroundDrawableTop();
            int right = cell.getBackgroundDrawableRight();
            int bottom = cell.getBackgroundDrawableBottom();
            if (right - left < dp(24) || bottom - top < dp(24)) {
                left = 0;
                top = 0;
                right = cell.getMeasuredWidth();
                bottom = cell.getMeasuredHeight();
            }
            final int m = dp(SNAPSHOT_MARGIN);
            cellLeft = left - m;
            cellTop = top - m;
            snapshotWidth = right - left + 2 * m;
            snapshotHeight = Math.min(bottom - top + 2 * m, AndroidUtilities.displaySize.y);
            try {
                snapshot = Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.ARGB_8888);
                final Canvas canvas = new Canvas(snapshot);
                canvas.translate(-cellLeft, -cellTop);
                if (cell.drawBackgroundInParent()) {
                    canvas.save();
                    canvas.translate(0, cell.getPaddingTop());
                    cell.drawBackgroundInternal(canvas, true);
                    canvas.restore();
                }
                cell.draw(canvas);
                if (cell.hasOutboundsContent()) {
                    canvas.save();
                    canvas.translate(0, cell.getPaddingTop());
                    cell.drawOutboundsContent(canvas);
                    canvas.restore();
                }
            } catch (Throwable e) {
                // a cell that cannot draw into a software canvas (hardware bitmaps, render nodes):
                // the copy becomes a plain card of the bubble's size and colour
                FileLog.e(e);
                recycle();
                try {
                    snapshot = Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.ARGB_8888);
                    final Canvas canvas = new Canvas(snapshot);
                    final MessageObject messageObject = cell.getMessageObject();
                    paint.setColor(Theme.getColor(messageObject != null && messageObject.isOutOwner() ? Theme.key_chat_outBubble : Theme.key_chat_inBubble, resourcesProvider));
                    canvas.drawRoundRect(m, m, snapshotWidth - m, snapshotHeight - m, dp(12), dp(12), paint);
                    paint.setColor(Color.WHITE);
                } catch (Throwable e2) {
                    FileLog.e(e2);
                    recycle();
                    return false;
                }
            }
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            lp.width = snapshotWidth;
            lp.height = snapshotHeight;
            setLayoutParams(lp);
            invalidateOutline();
            return true;
        }

        void recycle() {
            if (snapshot != null) {
                snapshot.recycle();
                snapshot = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (snapshot != null && !snapshot.isRecycled()) {
                canvas.drawBitmap(snapshot, 0, 0, paint);
            }
            if (countText != null) {
                // the badge sits on the top-right corner of the bubble, inside the shadow margin
                final float r = dp(11);
                final float cx = snapshotWidth - dp(SNAPSHOT_MARGIN);
                final float cy = dp(SNAPSHOT_MARGIN);
                final float w = Math.max(2 * r, countTextWidth + dp(12));
                canvas.drawRoundRect(cx - w / 2f, cy - r, cx + w / 2f, cy + r, r, r, badgePaint);
                canvas.drawText(countText, cx - countTextWidth / 2f, cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint);
            }
        }
    }
}
