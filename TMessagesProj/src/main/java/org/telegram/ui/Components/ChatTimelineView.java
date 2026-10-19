package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatTimelineIndex;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.Calendar;

/**
 * Chronologie — a timeline scrubber for the chat history.
 *
 * Lives on the trailing edge of the chat list, on top of it. A round handle
 * appears while the user scrolls, aligned with the date currently shown at the
 * top of the chat within the whole history. Grabbing the handle unfolds a ruler
 * of years (and months, once sampled) along the edge; dragging scrubs through
 * the history, a bubble next to the finger shows the month under it, and
 * releasing jumps the chat to that date (through ChatActivity.jumpToDate).
 * A tap without movement opens the existing calendar.
 *
 * The ruler is proportional to content, not to time: a year with ten thousand
 * messages takes ten times the height of a year with a thousand, with a floor
 * so that every year stays legible. The mapping comes from ChatTimelineIndex.
 * Without an index (or before it is ready) the handle is not shown.
 *
 * The view consumes touches only on the handle, so the ordinary scrolling of
 * the chat is untouched.
 */
public class ChatTimelineView extends View implements ChatTimelineIndex.Listener {

    public interface Delegate {
        /** Called on release after a drag; date is in unix seconds. */
        void onJumpToDate(int date);
        /** Called on a tap without movement; date is the date currently shown by the chat. */
        void onOpenCalendar(int date);
        /** Whether the handle may be shown right now (false during selection mode, search, etc). */
        boolean canShow();
        /** The user grabbed (true) or released (false) the handle: the chat can move its side buttons out of the way. */
        void onDragChanged(boolean dragging);
    }

    public static final int WIDTH_DP = 220; // wide enough to hold the ruler and the bubble; touches outside the handle fall through

    private static final long HIDE_DELAY_MS = 5000; // long enough to reach the disc after the scroll stops; underneath lie the share buttons of channel posts
    private static final float HANDLE_RADIUS_DP = 22f;
    private static final float HANDLE_EDGE_INSET_DP = 26f; // whole disc visible, 4 dp from the edge, easy to grab
    private static final float VERTICAL_MARGIN_DP = 8f;
    /** Kept free above the composer, where the chat's side buttons live, so the handle sits under the thumb. */
    private static final float BOTTOM_RESERVE_DP = 120f;
    private static final float BAR_MAX_DP = 20f;
    private static final float BAR_THICKNESS_DP = 2f;
    private static final float PANEL_WIDTH_DP = 108f;
    private static final float YEAR_FLOOR_DP = 28f;
    private static final float LABEL_MIN_HEIGHT_DP = 14f;
    /** The finger must rest this long on a month before it is measured: a sweep across the ruler costs nothing. */
    private static final long REFINE_REST_MS = 500;
    private static final int PANEL_MIN_ALPHA = 178;
    private static final int BUBBLE_MIN_ALPHA = 204;

    private final Theme.ResourcesProvider resourcesProvider;
    private Delegate delegate;
    private ChatTimelineIndex index;

    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint yearTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleSubPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevronPath = new Path();
    private final RectF bubbleRect = new RectF();
    private final RectF panelRect = new RectF();
    private final RectF hitRect = new RectF();
    private final Drawable shadowDrawable;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shadowRadius;

    private final AnimatedFloat showProgress;
    private final AnimatedFloat dragProgress;

    private int panelBaseAlpha = 255;
    private int bubbleBaseAlpha = 255;

    private final boolean rtl;
    private final float touchSlop;

    private boolean allowed;
    private boolean shown;
    private boolean scrollActive;
    private boolean dragging;
    private boolean moved;

    private float boundsTop;
    private float boundsBottom;

    /** 0 = top of the ruler (oldest), 1 = bottom (newest), like the chat itself. */
    private float progress = 1f;
    private int currentDate;

    private float downY;
    private float startProgress;

    private int bubbleDate;
    private int bubbleMonthKey = -1;
    private String bubbleText;
    private float bubbleTextWidth;
    private String bubbleSubText;
    private float bubbleSubWidth;

    // year blocks of the ruler, laid out for the current travel height
    private int blockCount;
    private int[] blockYear = new int[0];
    private int[] blockStartRank = new int[0];
    private int[] blockMessages = new int[0];
    private float[] blockTop = new float[0];
    private float[] blockHeight = new float[0];
    // one bar per month of every year block (12 per block): vertical position and length (0 = unknown)
    private float[] barY = new float[0];
    private float[] barLength = new float[0];
    private String[] blockLabel = new String[0];
    private float blocksTravel = -1f;
    private int blocksTotal = -1;
    private int blocksVersion = -1;
    private int indexVersion;

    private final Calendar calendar = Calendar.getInstance();

    // the months around the finger are only requested once it has rested on a month for a moment
    private int pendingRefineKey = -1;
    private int pendingRefineDate;
    private final Runnable refineRunnable = () -> {
        if (dragging && index != null && pendingRefineDate > 0) {
            index.refineAround(pendingRefineDate);
        }
    };

    // diagnostics: a small readout of the index traffic and of the time the last jump took, for test builds
    public static final boolean DEBUG_READOUT = false;
    private final TextPaint readoutPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint readoutBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF readoutRect = new RectF();
    private String readoutLine1;
    private String readoutLine2;
    private float readoutWidth;
    private long jumpRequestedAt;
    private long lastJumpMs = -1;

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
        dragProgress = new AnimatedFloat(this, 220, CubicBezierInterpolator.EASE_OUT_QUINT);

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(AndroidUtilities.dp(2));
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(AndroidUtilities.dp(1));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        bubbleTextPaint.setTextSize(AndroidUtilities.dp(14));
        bubbleTextPaint.setTypeface(AndroidUtilities.bold());

        yearTextPaint.setTextSize(AndroidUtilities.dp(11));
        yearTextPaint.setTypeface(AndroidUtilities.bold());
        yearTextPaint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);

        bubbleSubPaint.setTextSize(AndroidUtilities.dp(11));

        barPaint.setStyle(Paint.Style.STROKE);
        barPaint.setStrokeWidth(AndroidUtilities.dp(BAR_THICKNESS_DP));
        barPaint.setStrokeCap(Paint.Cap.ROUND);

        readoutPaint.setTextSize(AndroidUtilities.dp(10));
        readoutPaint.setTypeface(Typeface.MONOSPACE);

        shadowDrawable = null; // the disc no longer hugs the edge: a soft radial shadow replaces the edge drawable
        float r = AndroidUtilities.dp(HANDLE_RADIUS_DP);
        shadowRadius = r + AndroidUtilities.dp(8);
        shadowPaint.setShader(new android.graphics.RadialGradient(0f, 0f, shadowRadius,
                new int[]{0x4C000000, 0x33000000, 0x00000000}, new float[]{0f, r / shadowRadius, 1f}, android.graphics.Shader.TileMode.CLAMP));

        updateColors();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void setIndex(ChatTimelineIndex index) {
        if (this.index != null) {
            this.index.setListener(null);
        }
        this.index = index;
        blocksVersion = -1;
        if (index != null) {
            index.setListener(this);
        }
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

    /** The chat scrolled under the finger (or is flinging): show the handle, once the index allows it. */
    public void onChatScrolled() {
        if (!allowed || (delegate != null && !delegate.canShow())) {
            return;
        }
        scrollActive = true;
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        if (index == null) {
            return;
        }
        index.load();
        if (index.isUsable()) {
            if (!shown) {
                shown = true;
                progress = progressForDate(currentDate);
                invalidate();
            }
        }
    }

    /** The chat stopped scrolling: hide the handle after a short delay. */
    public void onChatScrollIdle() {
        scrollActive = false;
        scheduleHide(HIDE_DELAY_MS);
    }

    public void hideNow() {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        scrollActive = false;
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
        if (jumpRequestedAt != 0) {
            // the chat shows another date for the first time since the release: the jump has landed
            lastJumpMs = SystemClock.elapsedRealtime() - jumpRequestedAt;
            jumpRequestedAt = 0;
            if (DEBUG_READOUT) {
                updateReadout();
                shown = allowed;
                scheduleHide(HIDE_DELAY_MS);
            }
        }
        if (!dragging) {
            progress = progressForDate(date);
            if (shown) {
                invalidate();
            }
        }
    }

    /** Rebuilds the two lines of the debug readout; never called from onDraw. */
    private void updateReadout() {
        if (!DEBUG_READOUT) {
            return;
        }
        StringBuilder b = new StringBuilder(64);
        b.append(BuildVars.BUILD_VERSION_STRING);
        if (index != null) {
            b.append(" · env ").append(index.sentCount).append(" · rép ").append(index.answeredCount).append(" · err ").append(index.errorCount);
        }
        readoutLine1 = b.toString();
        b.setLength(0);
        if (index != null) {
            b.append("flood ").append(index.floodCount);
            if (index.floodCount > 0) {
                b.append(" (").append(index.lastFloodSeconds).append(" s)");
            }
            b.append(" · ");
        }
        b.append("saut ");
        if (jumpRequestedAt != 0) {
            b.append("en cours");
        } else if (lastJumpMs < 0) {
            b.append("—");
        } else {
            b.append(lastJumpMs / 1000).append(',').append((lastJumpMs % 1000) / 100).append(" s");
        }
        readoutLine2 = b.toString();
        readoutWidth = Math.max(readoutPaint.measureText(readoutLine1), readoutPaint.measureText(readoutLine2));
    }

    /** Vertical span of the chat area, in this view's coordinates (below the top panels, above the composer). */
    public void setBounds(float top, float bottom) {
        if (Math.abs(top - boundsTop) < 0.5f && Math.abs(bottom - boundsBottom) < 0.5f) {
            return;
        }
        boundsTop = top;
        boundsBottom = bottom;
        if (!dragging) {
            progress = progressForDate(currentDate);
        }
        if (shown || dragging) {
            invalidate();
        }
    }

    public void updateColors() {
        int background = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        handlePaint.setColor(ColorUtils.blendARGB(background, Color.WHITE, 0.1f));
        chevronPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        int service = Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider);
        // the service colour is tuned for a small date pill over the wallpaper; the ruler and the bubble sit over
        // message bubbles too, and carry text, so they are given a floor of opacity (light themes need it most)
        bubbleBaseAlpha = Math.max(Color.alpha(service), BUBBLE_MIN_ALPHA);
        bubblePaint.setColor(ColorUtils.setAlphaComponent(service, bubbleBaseAlpha));
        panelBaseAlpha = Math.max(Math.min(255, (int) (Color.alpha(service) * 1.15f)), PANEL_MIN_ALPHA);
        panelPaint.setColor(ColorUtils.setAlphaComponent(service, panelBaseAlpha));
        int text = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
        bubbleTextPaint.setColor(text);
        yearTextPaint.setColor(text);
        tickPaint.setColor(ColorUtils.setAlphaComponent(text, 110));
        barPaint.setColor(ColorUtils.setAlphaComponent(text, 150));
        bubbleSubPaint.setColor(ColorUtils.setAlphaComponent(text, 200));
        readoutPaint.setColor(text);
        readoutBackground.setColor(service);
        invalidate();
    }

    public void destroy() {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        AndroidUtilities.cancelRunOnUIThread(refineRunnable);
        if (index != null) {
            index.setListener(null);
        }
        dragging = false;
        shown = false;
    }

    @Override
    public void onIndexUpdated(ChatTimelineIndex index) {
        indexVersion++;
        updateReadout();
        if (!dragging) {
            progress = progressForDate(currentDate);
        } else if (bubbleDate > 0) {
            bubbleMonthKey = -1;
            updateBubble(bubbleDate, false);
        }
        if (!shown && scrollActive && allowed && index.isUsable() && (delegate == null || delegate.canShow())) {
            shown = true;
        }
        if (shown || dragging) {
            invalidate();
        }
    }

    private void scheduleHide(long delay) {
        AndroidUtilities.cancelRunOnUIThread(hideRunnable);
        AndroidUtilities.runOnUIThread(hideRunnable, delay);
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
        return bottom - AndroidUtilities.dp(VERTICAL_MARGIN_DP + BOTTOM_RESERVE_DP) - handleRadius();
    }

    private float handleCenterY() {
        float top = travelTop();
        float bottom = travelBottom();
        if (bottom <= top) {
            return top;
        }
        return top + (bottom - top) * clamp01(progress);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ---- year blocks: the ruler is proportional to content with a floor per year ----

    private void ensureBlocks() {
        if (index == null || !index.isReady()) {
            blockCount = 0;
            return;
        }
        float travel = travelBottom() - travelTop();
        if (travel <= 0) {
            blockCount = 0;
            return;
        }
        if (blocksVersion == indexVersion && Math.abs(blocksTravel - travel) < 0.5f && blocksTotal == index.totalCount) {
            return;
        }
        blocksVersion = indexVersion;
        blocksTravel = travel;
        blocksTotal = index.totalCount;

        int firstYear = index.getFirstYear();
        int lastYear = index.getLastYear();
        int n = Math.max(1, lastYear - firstYear + 1);
        if (blockYear.length < n) {
            blockYear = new int[n];
            blockStartRank = new int[n];
            blockMessages = new int[n];
            blockTop = new float[n];
            blockHeight = new float[n];
            blockLabel = new String[n];
            barY = new float[n * 12];
            barLength = new float[n * 12];
        }
        blockCount = n;
        int total = Math.max(1, index.totalCount);
        for (int i = 0; i < n; i++) {
            int year = firstYear + i;
            blockYear[i] = year;
            blockStartRank[i] = i == 0 ? 0 : index.rankForYearStart(year);
            int end = i == n - 1 ? total : index.rankForYearStart(year + 1);
            blockMessages[i] = Math.max(0, end - blockStartRank[i]);
            if (blockLabel[i] == null || !blockLabel[i].equals(String.valueOf(year))) {
                blockLabel[i] = String.valueOf(year);
            }
        }

        // proportional heights, raised to a floor, the rest shrunk to keep the sum
        float floor = Math.min(AndroidUtilities.dp(YEAR_FLOOR_DP), travel / n);
        boolean[] raised = new boolean[n];
        for (int pass = 0; pass < 6; pass++) {
            float fixed = 0f;
            int freeMessages = 0;
            for (int i = 0; i < n; i++) {
                if (raised[i]) {
                    fixed += floor;
                } else {
                    freeMessages += blockMessages[i];
                }
            }
            float free = Math.max(0f, travel - fixed);
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (raised[i]) {
                    blockHeight[i] = floor;
                } else {
                    float h = freeMessages > 0 ? free * blockMessages[i] / freeMessages : free / Math.max(1, n);
                    if (h < floor) {
                        raised[i] = true;
                        changed = true;
                        blockHeight[i] = floor;
                    } else {
                        blockHeight[i] = h;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        float y = travelTop();
        for (int i = 0; i < n; i++) {
            blockTop[i] = y;
            y += blockHeight[i];
        }
        layoutMonthBars();
    }

    /** One bar per sampled month: placed at the middle of the month's band, as long as the month was busy. */
    private void layoutMonthBars() {
        int maxCount = 0;
        for (int i = 0; i < blockCount; i++) {
            int year = blockYear[i];
            for (int m = 0; m < 12; m++) {
                int k = i * 12 + m;
                int from = index.startOf(year, m);
                int to = m == 11 ? index.startOf(year + 1, 0) : index.startOf(year, m + 1);
                if (to <= index.firstDate || from > index.lastDate || !index.isMonthRefined(year, m)) {
                    barLength[k] = 0f;
                    continue;
                }
                int count = index.countForMonth(year, m);
                float y1 = yForDate(Math.max(from, index.firstDate));
                float y2 = yForDate(Math.min(to, index.lastDate + 1));
                barY[k] = (y1 + y2) / 2f;
                barLength[k] = count;
                maxCount = Math.max(maxCount, count);
            }
        }
        float maxLength = AndroidUtilities.dp(BAR_MAX_DP);
        float minLength = AndroidUtilities.dp(3);
        for (int k = 0; k < blockCount * 12; k++) {
            if (barLength[k] > 0f) {
                barLength[k] = minLength + (maxLength - minLength) * (float) Math.sqrt(barLength[k] / (float) Math.max(1, maxCount));
            }
        }
    }

    /** Vertical position (in this view) of a date within the ruler. */
    private float yForDate(int date) {
        ensureBlocks();
        if (blockCount == 0 || index == null) {
            return travelBottom();
        }
        int rank = index.rankForDate(date);
        for (int i = 0; i < blockCount; i++) {
            int start = blockStartRank[i];
            int count = blockMessages[i];
            if (rank < start + count || i == blockCount - 1) {
                float f = count > 0 ? clamp01((rank - start) / (float) count) : 0f;
                return blockTop[i] + f * blockHeight[i];
            }
        }
        return travelBottom();
    }

    /** Date under a vertical position of the ruler. */
    private int dateForY(float y) {
        ensureBlocks();
        if (blockCount == 0 || index == null) {
            return 0;
        }
        for (int i = 0; i < blockCount; i++) {
            if (y < blockTop[i] + blockHeight[i] || i == blockCount - 1) {
                float f = blockHeight[i] > 0 ? clamp01((y - blockTop[i]) / blockHeight[i]) : 0f;
                int rank = blockStartRank[i] + Math.round(f * blockMessages[i]);
                return index.dateForRank(rank);
            }
        }
        return index.lastDate;
    }

    private int yearForY(float y) {
        ensureBlocks();
        for (int i = 0; i < blockCount; i++) {
            if (y < blockTop[i] + blockHeight[i] || i == blockCount - 1) {
                return blockYear[i];
            }
        }
        return 0;
    }

    private float progressForDate(int date) {
        float top = travelTop();
        float bottom = travelBottom();
        if (bottom <= top || date <= 0 || index == null || !index.isReady()) {
            return 1f;
        }
        return clamp01((yForDate(date) - top) / (bottom - top));
    }

    private float progressForY(float y) {
        float top = travelTop();
        float bottom = travelBottom();
        if (bottom <= top) {
            return 1f;
        }
        return clamp01((y - top) / (bottom - top));
    }

    private float yForProgress(float p) {
        return travelTop() + (travelBottom() - travelTop()) * clamp01(p);
    }

    /** Refines the months around the finger after a short rest, so that a sweep across the ruler costs nothing. */
    private void requestRefine(int date) {
        if (date <= 0) {
            return;
        }
        calendar.setTimeInMillis(date * 1000L);
        int key = calendar.get(Calendar.YEAR) * 12 + calendar.get(Calendar.MONTH);
        if (key == pendingRefineKey) {
            return;
        }
        pendingRefineKey = key;
        pendingRefineDate = date;
        AndroidUtilities.cancelRunOnUIThread(refineRunnable);
        AndroidUtilities.runOnUIThread(refineRunnable, REFINE_REST_MS);
    }

    // ---- bubble ----

    private void updateBubble(int date, boolean haptic) {
        if (date <= 0) {
            return;
        }
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
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH);
        if (index != null && index.isReady()) {
            // exact once both ends of the month are sampled, an estimate (interpolated) before that
            String count = LocaleController.formatPluralString("messages", index.countForMonth(year, month));
            bubbleSubText = index.isMonthRefined(year, month) ? count : "≈ " + count;
            bubbleSubWidth = bubbleSubPaint.measureText(bubbleSubText);
        } else {
            bubbleSubText = null;
            bubbleSubWidth = 0f;
        }
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
                if (!shown || index == null || !index.isUsable()) {
                    return false;
                }
                float cx = handleCenterX();
                float cy = handleCenterY();
                float r = handleRadius() + AndroidUtilities.dp(12);
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
                pendingRefineKey = -1;
                int grabbedDate = currentDate > 0 ? currentDate : dateForY(cy);
                updateBubble(grabbedDate, false);
                requestRefine(grabbedDate);
                if (delegate != null) {
                    delegate.onDragChanged(true);
                }
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
                    float travel = Math.max(1f, travelBottom() - travelTop());
                    progress = clamp01(startProgress + dy / travel);
                    float handleY = yForProgress(progress);
                    int dateUnderFinger = dateForY(handleY);
                    updateBubble(dateUnderFinger, true);
                    requestRefine(dateUnderFinger);
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
                AndroidUtilities.cancelRunOnUIThread(refineRunnable);
                pendingRefineKey = -1;
                pendingRefineDate = 0;
                if (index != null) {
                    // nothing of the index may go out around the jump: the queue is dropped and the pump held
                    index.cancelPending();
                    index.holdFor(1500);
                }
                if (delegate != null) {
                    delegate.onDragChanged(false);
                    if (moved) {
                        int date = dateForY(yForProgress(progress));
                        if (date > 0) {
                            jumpRequestedAt = SystemClock.elapsedRealtime();
                            lastJumpMs = -1;
                            updateReadout();
                            delegate.onJumpToDate(date);
                        }
                    } else {
                        delegate.onOpenCalendar(currentDate > 0 ? currentDate : (int) (System.currentTimeMillis() / 1000));
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
                AndroidUtilities.cancelRunOnUIThread(refineRunnable);
                pendingRefineKey = -1;
                pendingRefineDate = 0;
                if (index != null) {
                    index.cancelPending();
                }
                if (delegate != null) {
                    delegate.onDragChanged(false);
                }
                progress = progressForDate(currentDate);
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
        float drag = dragProgress.set(dragging ? 1f : 0f);
        if (visible <= 0f) {
            return;
        }
        float cx = handleCenterX();
        float cy = handleCenterY();
        float r = handleRadius();
        int alpha = (int) (255 * visible);
        int width = getMeasuredWidth();

        // the ruler of years, unfolding from the edge while dragging
        if (drag > 0f) {
            ensureBlocks();
            float panelWidth = AndroidUtilities.dp(PANEL_WIDTH_DP) * drag;
            float top = travelTop() - r - AndroidUtilities.dp(4);
            float bottom = travelBottom() + r + AndroidUtilities.dp(4);
            float radius = AndroidUtilities.dp(14);
            if (rtl) {
                panelRect.set(-radius, top, panelWidth, bottom);
            } else {
                panelRect.set(width - panelWidth, top, width + radius, bottom);
            }
            panelPaint.setAlpha((int) (panelBaseAlpha * drag * visible));
            canvas.drawRoundRect(panelRect, radius, radius, panelPaint);

            if (blockCount > 0 && drag > 0.5f) {
                float textAlpha = (drag - 0.5f) * 2f * visible;
                yearTextPaint.setAlpha((int) (255 * textAlpha));
                tickPaint.setAlpha((int) (110 * textAlpha));
                float labelX = rtl ? AndroidUtilities.dp(54) : width - AndroidUtilities.dp(54);
                float tickFrom = rtl ? AndroidUtilities.dp(4) : width - AndroidUtilities.dp(PANEL_WIDTH_DP - 4);
                float tickTo = rtl ? AndroidUtilities.dp(10) : width - AndroidUtilities.dp(PANEL_WIDTH_DP - 10);
                float minLabel = AndroidUtilities.dp(LABEL_MIN_HEIGHT_DP);
                float textOffset = -(yearTextPaint.descent() + yearTextPaint.ascent()) / 2f;
                barPaint.setAlpha((int) (150 * textAlpha));
                float barFrom = rtl ? AndroidUtilities.dp(4) : width - AndroidUtilities.dp(PANEL_WIDTH_DP - 4);
                for (int i = 0; i < blockCount; i++) {
                    if (i > 0) {
                        canvas.drawLine(tickFrom, blockTop[i], tickTo, blockTop[i], tickPaint);
                    }
                    if (blockHeight[i] >= minLabel) {
                        canvas.drawText(blockLabel[i], labelX, blockTop[i] + blockHeight[i] / 2f + textOffset, yearTextPaint);
                    }
                    for (int m = 0; m < 12; m++) {
                        int k = i * 12 + m;
                        if (barLength[k] > 0f) {
                            canvas.drawLine(barFrom, barY[k], barFrom + barLength[k], barY[k], barPaint);
                        }
                    }
                }
            }
        }

        // soft shadow under the disc
        canvas.save();
        canvas.translate(cx, cy + AndroidUtilities.dp(2));
        shadowPaint.setAlpha(alpha);
        canvas.drawCircle(0f, 0f, shadowRadius, shadowPaint);
        canvas.restore();
        if (shadowDrawable != null) {
            int w = shadowDrawable.getIntrinsicWidth();
            int h = shadowDrawable.getIntrinsicHeight();
            int left = rtl ? 0 : width - w;
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

        // the bubble with the month under the finger, left of the ruler
        if (drag > 0f && bubbleText != null) {
            float padH = AndroidUtilities.dp(12);
            float padV = AndroidUtilities.dp(7);
            float textH = bubbleTextPaint.descent() - bubbleTextPaint.ascent();
            float subH = bubbleSubText != null ? bubbleSubPaint.descent() - bubbleSubPaint.ascent() + AndroidUtilities.dp(1) : 0f;
            float bubbleWidth = Math.max(bubbleTextWidth, bubbleSubWidth) + padH * 2;
            float bubbleHeight = textH + subH + padV * 2;
            float edge = AndroidUtilities.dp(PANEL_WIDTH_DP + 8);
            float left = rtl ? edge : width - edge - bubbleWidth;
            bubbleRect.set(left, cy - bubbleHeight / 2f, left + bubbleWidth, cy + bubbleHeight / 2f);

            canvas.save();
            float s = 0.7f + 0.3f * drag;
            canvas.scale(s, s, rtl ? bubbleRect.left : bubbleRect.right, cy);
            bubblePaint.setAlpha((int) (bubbleBaseAlpha * drag * visible));
            canvas.drawRoundRect(bubbleRect, bubbleHeight / 2f, bubbleHeight / 2f, bubblePaint);
            bubbleTextPaint.setAlpha((int) (255 * drag * visible));
            float textTop = bubbleRect.top + padV;
            canvas.drawText(bubbleText, bubbleRect.left + padH, textTop - bubbleTextPaint.ascent(), bubbleTextPaint);
            if (bubbleSubText != null) {
                bubbleSubPaint.setAlpha((int) (200 * drag * visible));
                canvas.drawText(bubbleSubText, bubbleRect.left + padH, textTop + textH + AndroidUtilities.dp(1) - bubbleSubPaint.ascent(), bubbleSubPaint);
            }
            canvas.restore();
        }

        // the debug readout, top of the view, out of the way of the ruler
        if (DEBUG_READOUT && readoutLine1 != null) {
            float pad = AndroidUtilities.dp(6);
            float lineH = readoutPaint.descent() - readoutPaint.ascent();
            float left = rtl ? width - readoutWidth - pad * 2 - AndroidUtilities.dp(4) : AndroidUtilities.dp(4);
            float top = boundsTop + AndroidUtilities.dp(4);
            readoutRect.set(left, top, left + readoutWidth + pad * 2, top + lineH * 2 + pad * 2);
            readoutBackground.setAlpha((int) (bubbleBaseAlpha * visible));
            canvas.drawRoundRect(readoutRect, AndroidUtilities.dp(8), AndroidUtilities.dp(8), readoutBackground);
            readoutPaint.setAlpha(alpha);
            canvas.drawText(readoutLine1, left + pad, top + pad - readoutPaint.ascent(), readoutPaint);
            canvas.drawText(readoutLine2, left + pad, top + pad + lineH - readoutPaint.ascent(), readoutPaint);
        }
    }
}
