package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.LruCache;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;

/**
 * Chronologie — the chronological index of a dialog.
 *
 * Knows, for a dialog, how many messages it holds, when the first and the last
 * were sent, and how many messages were sent before a set of boundary dates
 * (every 1st of January first, then the months around the finger on demand).
 * From that it maps a date to a rank in the history and back, which is what the
 * timeline scrubber needs to lay years out proportionally to their content.
 *
 * Everything comes from messages.getHistory with offset_date and limit 1: the
 * response carries count (total messages of the dialog) and offset_id_offset
 * (rank of the returned message counted from the newest), the very calculation
 * ChatActivity already performs to count a day's messages. Exact for every kind
 * of dialog; no new API, no schema change.
 *
 * The server rations messages.getHistory per account, on the total over several
 * minutes as much as on the pace: too many calls and every further call,
 * including the chat's own jump to a date, is held back for seconds or tens of
 * seconds. So the index is deliberately frugal: one request at a time, never two
 * closer than {@link #BASE_SEND_INTERVAL_MS}, a small burst then a steady
 * trickle, months sampled only around the finger, a full stop for the time the
 * server asks when a FLOOD_WAIT comes back — and what was measured once is kept
 * on the device, so that a dialog already known costs a single request to
 * refresh its end.
 *
 * All methods must be called on the UI thread.
 */
public class ChatTimelineIndex {

    public interface Listener {
        void onIndexUpdated(ChatTimelineIndex index);
    }

    /** Number of messages sent before {@code date} (unix seconds). */
    public static class Sample {
        public final int date;
        public final int rankFromOldest;

        Sample(int date, int rankFromOldest) {
            this.date = date;
            this.rankFromOldest = rankFromOldest;
        }
    }

    public static final int MIN_MESSAGES = 200;
    public static final int MIN_SPAN_SECONDS = 60 * 86400;

    private static final int MAX_IN_FLIGHT = 1;
    /** Minimum spacing between two requests; doubled every time the server complains, up to the maximum. */
    private static final long BASE_SEND_INTERVAL_MS = 400;
    private static final long MAX_SEND_INTERVAL_MS = 2000;
    /** Token bucket: a short burst (at the spacing above) for the bootstrap, then a trickle of one per REFILL_MS. */
    private static final int BURST = 6;
    private static final long REFILL_MS = 1000;
    /** After this many failed requests the index gives up for the session rather than nagging the server. */
    private static final int MAX_ERRORS = 12;
    private static final long RETRY_FAILED_AFTER_MS = 10_000;
    private static final int OLDEST_PROBE = -1;
    private static final int NEWEST_PROBE = -2;

    private static final String STORE_NAME = "chronologie_index";
    private static final int STORE_VERSION = 1;
    private static final int STORE_MAX_DIALOGS = 48;
    private static final long STORE_MAX_AGE_SECONDS = 30L * 86400;
    private static final long SAVE_DELAY_MS = 1500;

    private static final LruCache<String, ChatTimelineIndex> cache = new LruCache<>(16);

    /** The index of a dialog, or of one thread of it (forum topic, comments) when {@code threadMessageId} is not 0. */
    public static ChatTimelineIndex get(int currentAccount, long dialogId, long threadMessageId, int classGuid) {
        String key = currentAccount + "_" + dialogId + (threadMessageId != 0 ? "_" + threadMessageId : "");
        ChatTimelineIndex index = cache.get(key);
        if (index == null) {
            index = new ChatTimelineIndex(currentAccount, dialogId, threadMessageId);
            cache.put(key, index);
        }
        index.attach(classGuid);
        return index;
    }

    public final int currentAccount;
    public final long dialogId;
    /** 0 for the whole dialog; otherwise the thread is read with messages.getReplies, like ChatActivity does. */
    public final long threadMessageId;
    private int classGuid;

    public int totalCount = -1;
    public int firstDate;
    public int lastDate;
    public int firstMessageId;
    public int lastMessageId;

    private int sentCount;
    private int errorCount;

    private final ArrayList<Sample> samples = new ArrayList<>();
    private final HashSet<Integer> answered = new HashSet<>();
    private final HashSet<Integer> inFlightDates = new HashSet<>();
    private final HashSet<Integer> failed = new HashSet<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final ArrayList<Integer> wanted = new ArrayList<>();
    private final Calendar calendar = Calendar.getInstance();

    private boolean pumpScheduled;
    private long lastSendTime;
    private long sendInterval = BASE_SEND_INTERVAL_MS;
    private long nextTokenTime; // token bucket, GCRA form: theoretical time of the next request
    private long holdUntil;
    private long floodWaitUntil;
    private long lastFailureClear;
    private boolean bootstrapStarted;
    private boolean newestKnown;
    private boolean newestStale; // restored from the store: the newest message must be refreshed once
    private boolean oldestKnown;
    private boolean yearsComplete;
    private boolean saveScheduled;
    private Listener listener;

    private ChatTimelineIndex(int currentAccount, long dialogId, long threadMessageId) {
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.threadMessageId = threadMessageId;
        restore();
    }

    /** A (new) fragment uses this index: requests are bound to its guid; the ones the old fragment cancelled are forgotten. */
    public void attach(int classGuid) {
        if (this.classGuid != classGuid) {
            this.classGuid = classGuid;
            inFlightDates.clear();
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isReady() {
        return newestKnown && oldestKnown && totalCount > 0;
    }

    /** Enough history to be worth a timeline: the view stays hidden otherwise. */
    public boolean isUsable() {
        return isReady() && totalCount >= MIN_MESSAGES && lastDate - firstDate >= MIN_SPAN_SECONDS;
    }

    public boolean isLoading() {
        return !inFlightDates.isEmpty() || !queue.isEmpty();
    }

    /** Milliseconds left of a wait the server imposed, 0 when none. */
    public long getFloodWaitRemainingMs() {
        return Math.max(0, floodWaitUntil - SystemClock.elapsedRealtime());
    }

    /** A boundary is known when it was answered, or when it falls outside the history (rank 0 or total). */
    private boolean isKnown(int date) {
        return date <= firstDate || date > lastDate || answered.contains(date);
    }

    /** True once both ends of a month are known, so that its count is exact. */
    public boolean isMonthRefined(int year, int month) {
        if (!isReady()) {
            return false;
        }
        int from = startOf(year, month);
        int to = month == Calendar.DECEMBER ? startOf(year + 1, Calendar.JANUARY) : startOf(year, month + 1);
        return isKnown(from) && isKnown(to);
    }

    /** True once every month boundary of the year that falls inside the history has been answered. */
    public boolean isYearRefined(int year) {
        if (!isReady()) {
            return false;
        }
        for (int month = Calendar.JANUARY; month <= Calendar.DECEMBER; month++) {
            if (!isKnown(startOf(year, month))) {
                return false;
            }
        }
        return isKnown(startOf(year + 1, Calendar.JANUARY));
    }

    /** Drops every request not yet sent, so that nothing of the index is queued behind a jump the chat requests. */
    public void cancelPending() {
        queue.clear();
    }

    /** Keeps the index silent for a moment, typically while the chat performs a jump. */
    public void holdFor(long ms) {
        holdUntil = Math.max(holdUntil, SystemClock.elapsedRealtime() + ms);
    }

    public int getFirstYear() {
        return yearOf(firstDate);
    }

    public int getLastYear() {
        return yearOf(lastDate);
    }

    /** Starts loading: newest and oldest message, then every 1st of January. Cheap to call again. */
    public void load() {
        if (errorCount >= MAX_ERRORS || (yearsComplete && !newestStale)) {
            return;
        }
        if (!failed.isEmpty() && SystemClock.elapsedRealtime() - lastFailureClear > RETRY_FAILED_AFTER_MS) {
            // a boundary that failed is tried again, but not before a while
            failed.clear();
            lastFailureClear = SystemClock.elapsedRealtime();
        }
        if (!bootstrapStarted) {
            bootstrapStarted = true;
            queue.addFirst(NEWEST_PROBE);
            if (!oldestKnown) {
                queue.addLast(OLDEST_PROBE);
            }
        } else if (newestStale) {
            // restored from the store: the newest message is refreshed once, then the years checked
            if (!isPending(NEWEST_PROBE) && !failed.contains(NEWEST_PROBE)) {
                queue.addFirst(NEWEST_PROBE);
            }
        } else if (isReady()) {
            enqueueYears();
        } else {
            if (!newestKnown && !isPending(NEWEST_PROBE) && !failed.contains(NEWEST_PROBE)) {
                queue.addFirst(NEWEST_PROBE);
            }
            if (!oldestKnown && !isPending(OLDEST_PROBE) && !failed.contains(OLDEST_PROBE)) {
                queue.addLast(OLDEST_PROBE);
            }
        }
        pump();
    }

    /**
     * Asks for the month boundaries around {@code date}: the two enclosing the month under the finger, then
     * one more on each side, nearest first — never a whole year, the server counts every call. The queue is
     * replaced each time, so sweeping across the ruler never piles up requests: at most one is in flight,
     * the rest waits for the finger to settle.
     */
    public void refineAround(int date) {
        if (!isReady() || date <= 0) {
            return;
        }
        int year = yearOf(date);
        if (year < getFirstYear() || year > getLastYear()) {
            return;
        }
        int month = monthOf(date);
        wanted.clear();
        for (int m = month - 1; m <= month + 2; m++) {
            if (m < Calendar.FEBRUARY || m > Calendar.DECEMBER) {
                continue; // January boundaries belong to the bootstrap
            }
            int d = startOf(year, m);
            if (!isKnown(d) && !inFlightDates.contains(d) && !failed.contains(d)) {
                wanted.add(d);
            }
        }
        final int around = date;
        Collections.sort(wanted, (a, b) -> Integer.compare(Math.abs(a - around), Math.abs(b - around)));
        // the bootstrap (probes, year boundaries) stays ahead: it shapes the whole ruler
        for (int i = queue.size(); i > 0; i--) {
            int d = queue.pollFirst();
            if (d < 0 || isYearBoundary(d)) {
                queue.addLast(d);
            }
        }
        queue.addAll(wanted);
        pump();
    }

    private boolean isPending(int date) {
        return inFlightDates.contains(date) || queue.contains(date);
    }

    private boolean isYearBoundary(int date) {
        calendar.setTimeInMillis(date * 1000L);
        return calendar.get(Calendar.MONTH) == Calendar.JANUARY && calendar.get(Calendar.DAY_OF_MONTH) == 1
                && calendar.get(Calendar.HOUR_OF_DAY) == 0 && calendar.get(Calendar.MINUTE) == 0;
    }

    // ---- mapping ----

    /** Number of messages sent before {@code date}, interpolated between the known samples. */
    public int rankForDate(int date) {
        if (!isReady()) {
            return 0;
        }
        if (date <= firstDate) {
            return 0;
        }
        if (date > lastDate) {
            return totalCount;
        }
        int prevDate = firstDate, prevRank = 0;
        int nextDate = lastDate + 1, nextRank = totalCount;
        for (int i = 0, n = samples.size(); i < n; i++) {
            Sample s = samples.get(i);
            if (s.date <= date) {
                prevDate = s.date;
                prevRank = s.rankFromOldest;
            } else {
                nextDate = s.date;
                nextRank = s.rankFromOldest;
                break;
            }
        }
        if (nextDate <= prevDate) {
            return prevRank;
        }
        float f = (date - prevDate) / (float) (nextDate - prevDate);
        return prevRank + Math.round(f * (nextRank - prevRank));
    }

    /** Date (unix seconds) at which {@code rank} messages had been sent, interpolated between the known samples. */
    public int dateForRank(int rank) {
        if (!isReady()) {
            return 0;
        }
        if (rank <= 0) {
            return firstDate;
        }
        if (rank >= totalCount) {
            return lastDate;
        }
        int prevDate = firstDate, prevRank = 0;
        int nextDate = lastDate + 1, nextRank = totalCount;
        for (int i = 0, n = samples.size(); i < n; i++) {
            Sample s = samples.get(i);
            if (s.rankFromOldest <= rank) {
                prevDate = s.date;
                prevRank = s.rankFromOldest;
            } else {
                nextDate = s.date;
                nextRank = s.rankFromOldest;
                break;
            }
        }
        if (nextRank <= prevRank) {
            return prevDate;
        }
        float f = (rank - prevRank) / (float) (nextRank - prevRank);
        int date = prevDate + Math.round(f * (nextDate - prevDate));
        return Math.max(firstDate, Math.min(lastDate, date));
    }

    /** Messages sent in {@code year}. */
    public int countForYear(int year) {
        return rankForDate(startOf(year + 1, Calendar.JANUARY)) - rankForDate(startOf(year, Calendar.JANUARY));
    }

    /** Messages sent in a month ({@code month} as in Calendar, 0-based); exact once the month is refined, interpolated before. */
    public int countForMonth(int year, int month) {
        int from = startOf(year, month);
        int to = month == Calendar.DECEMBER ? startOf(year + 1, Calendar.JANUARY) : startOf(year, month + 1);
        return rankForDate(to) - rankForDate(from);
    }

    /** Rank at which {@code year} starts. */
    public int rankForYearStart(int year) {
        return rankForDate(startOf(year, Calendar.JANUARY));
    }

    public int yearOf(int date) {
        calendar.setTimeInMillis(date * 1000L);
        return calendar.get(Calendar.YEAR);
    }

    public int monthOf(int date) {
        calendar.setTimeInMillis(date * 1000L);
        return calendar.get(Calendar.MONTH);
    }

    /** Unix seconds of the first instant of a month, in the device's time zone. */
    public int startOf(int year, int month) {
        calendar.clear();
        calendar.set(year, month, 1, 0, 0, 0);
        return (int) (calendar.getTimeInMillis() / 1000);
    }

    // ---- loading ----

    private void enqueueYears() {
        boolean complete = true;
        int first = getFirstYear();
        int last = getLastYear();
        for (int year = first + 1; year <= last; year++) {
            int date = startOf(year, Calendar.JANUARY);
            if (isKnown(date)) {
                continue;
            }
            complete = false;
            if (!isPending(date) && !failed.contains(date)) {
                queue.addLast(date);
            }
        }
        yearsComplete = complete;
    }

    private void schedulePump(long delay) {
        if (pumpScheduled) {
            return;
        }
        pumpScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            pumpScheduled = false;
            pump();
        }, delay);
    }

    private void pump() {
        if (errorCount >= MAX_ERRORS) {
            queue.clear();
            return;
        }
        while (inFlightDates.size() < MAX_IN_FLIGHT && !queue.isEmpty()) {
            long now = SystemClock.elapsedRealtime();
            long earliest = Math.max(lastSendTime + sendInterval, nextTokenTime - (BURST - 1) * REFILL_MS);
            earliest = Math.max(earliest, Math.max(holdUntil, floodWaitUntil));
            if (earliest > now) {
                schedulePump(earliest - now + 5);
                return;
            }
            int date = queue.pollFirst();
            if (date >= 0 && (answered.contains(date) || inFlightDates.contains(date))) {
                continue;
            }
            lastSendTime = now;
            nextTokenTime = Math.max(now, nextTokenTime) + REFILL_MS;
            send(date);
        }
    }

    private void send(final int date) {
        int offsetDate = date == NEWEST_PROBE ? 0 : (date == OLDEST_PROBE ? 1 : date);
        int addOffset = date == OLDEST_PROBE ? -1 : 0;
        TLObject req;
        if (threadMessageId != 0) {
            TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
            replies.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            replies.msg_id = (int) threadMessageId;
            replies.limit = 1;
            replies.offset_date = offsetDate;
            replies.add_offset = addOffset;
            req = replies;
        } else {
            TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
            history.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            history.limit = 1;
            history.offset_date = offsetDate;
            history.add_offset = addOffset;
            req = history;
        }
        inFlightDates.add(date);
        sentCount++;
        final int guid = classGuid;
        // FailOnServerErrors: a FLOOD_WAIT must reach us, not be retried silently by the network layer
        int reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            inFlightDates.remove(date);
            handle(date, response, error);
            if (guid == classGuid) {
                pump();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
        ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(reqId, classGuid);
    }

    private void handle(int date, TLObject response, TLRPC.TL_error error) {
        if (error != null) {
            if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                int seconds = 5;
                try {
                    seconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
                } catch (Exception ignore) {}
                floodWaitUntil = SystemClock.elapsedRealtime() + (seconds + 1) * 1000L;
                sendInterval = Math.min(MAX_SEND_INTERVAL_MS, sendInterval * 2);
                queue.addFirst(date);
                FileLog.d("ChatTimelineIndex: FLOOD_WAIT " + seconds + "s after " + sentCount + " requests, spacing now " + sendInterval + "ms");
            } else {
                errorCount++;
                failed.add(date);
                FileLog.d("ChatTimelineIndex: request for " + date + " failed: " + error.text);
            }
            if (listener != null) {
                listener.onIndexUpdated(this);
            }
            return;
        }
        if (!(response instanceof TLRPC.messages_Messages)) {
            errorCount++;
            failed.add(date);
            return;
        }
        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        MessagesController.getInstance(currentAccount).putUsers(res.users, false);
        MessagesController.getInstance(currentAccount).putChats(res.chats, false);
        answered.add(date);

        boolean hasCount = !(res instanceof TLRPC.TL_messages_messages);
        TLRPC.Message message = res.messages.isEmpty() ? null : res.messages.get(0);

        if (date == NEWEST_PROBE) {
            int newTotal = message == null ? 0 : (hasCount ? res.count : res.messages.size());
            if (newestStale && totalCount > 0 && newTotal < totalCount * 0.9f) {
                // many messages were deleted since the index was stored: the stored ranks are no longer trustworthy
                samples.clear();
                answered.clear();
                yearsComplete = false;
                FileLog.d("ChatTimelineIndex: stored samples dropped, total went from " + totalCount + " to " + newTotal);
            }
            newestStale = false;
            totalCount = newTotal;
            if (message != null) {
                lastDate = message.date;
                lastMessageId = message.id;
                FileLog.d("ChatTimelineIndex: newest message date=" + lastDate + " total=" + totalCount + " offset=" + res.offset_id_offset + " hasCount=" + hasCount);
            }
            newestKnown = true;
        } else if (date == OLDEST_PROBE) {
            if (message != null) {
                firstDate = message.date;
                firstMessageId = message.id;
                FileLog.d("ChatTimelineIndex: oldest message date=" + firstDate + " offset=" + res.offset_id_offset + " (expected total-1=" + (totalCount - 1) + ")");
            } else {
                firstDate = lastDate;
            }
            oldestKnown = true;
        } else {
            // the newest message sent before 'date', ranked from the newest: everything else is after it
            int rankFromOldest;
            if (message == null) {
                rankFromOldest = 0;
            } else {
                rankFromOldest = Math.max(0, Math.min(totalCount, totalCount - res.offset_id_offset));
            }
            insertSample(date, rankFromOldest);
        }

        if (isReady() && !yearsComplete) {
            enqueueYears();
        }
        scheduleSave();
        if (listener != null) {
            listener.onIndexUpdated(this);
        }
    }

    // ---- store: what was measured is kept on the device, one line per dialog ----

    private static SharedPreferences store() {
        return ApplicationLoader.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
    }

    private String storeKey() {
        return currentAccount + "_" + dialogId + (threadMessageId != 0 ? "_" + threadMessageId : "");
    }

    private void restore() {
        String line;
        try {
            line = store().getString(storeKey(), null);
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        if (line == null) {
            return;
        }
        try {
            String[] f = line.split(";");
            if (f.length < 8 || Integer.parseInt(f[0]) != STORE_VERSION) {
                return;
            }
            long savedAt = Long.parseLong(f[6]);
            if (System.currentTimeMillis() / 1000 - savedAt > STORE_MAX_AGE_SECONDS) {
                return;
            }
            firstDate = Integer.parseInt(f[1]);
            firstMessageId = Integer.parseInt(f[2]);
            lastDate = Integer.parseInt(f[3]);
            lastMessageId = Integer.parseInt(f[4]);
            totalCount = Integer.parseInt(f[5]);
            if (f[7].length() > 0) {
                for (String pair : f[7].split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    int date = Integer.parseInt(pair.substring(0, colon));
                    int rank = Integer.parseInt(pair.substring(colon + 1));
                    insertSample(date, rank);
                    answered.add(date);
                }
            }
            oldestKnown = true;
            newestKnown = totalCount > 0;
            newestStale = true;
            FileLog.d("ChatTimelineIndex: restored " + samples.size() + " samples for " + dialogId);
        } catch (Exception e) {
            FileLog.e(e);
            samples.clear();
            answered.clear();
            oldestKnown = newestKnown = newestStale = false;
            totalCount = -1;
        }
    }

    private void scheduleSave() {
        if (saveScheduled || !isReady() || newestStale) {
            return;
        }
        saveScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            saveScheduled = false;
            save();
        }, SAVE_DELAY_MS);
    }

    private void save() {
        if (!isReady() || newestStale) {
            return;
        }
        StringBuilder b = new StringBuilder(64 + samples.size() * 22);
        b.append(STORE_VERSION).append(';').append(firstDate).append(';').append(firstMessageId).append(';')
                .append(lastDate).append(';').append(lastMessageId).append(';').append(totalCount).append(';')
                .append(System.currentTimeMillis() / 1000).append(';');
        for (int i = 0, n = samples.size(); i < n; i++) {
            Sample s = samples.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append(s.date).append(':').append(s.rankFromOldest);
        }
        try {
            SharedPreferences prefs = store();
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(storeKey(), b.toString());
            // keep the store small: the dialogs saved the longest ago make room
            java.util.Map<String, ?> all = prefs.getAll();
            if (all.size() >= STORE_MAX_DIALOGS) {
                String oldestKey = null;
                long oldestAt = Long.MAX_VALUE;
                for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
                    if (entry.getKey().equals(storeKey()) || !(entry.getValue() instanceof String)) {
                        continue;
                    }
                    String[] f = ((String) entry.getValue()).split(";");
                    long at = 0;
                    try {
                        at = f.length > 6 ? Long.parseLong(f[6]) : 0;
                    } catch (Exception ignore) {}
                    if (at < oldestAt) {
                        oldestAt = at;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) {
                    editor.remove(oldestKey);
                }
            }
            editor.apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void insertSample(int date, int rankFromOldest) {
        int index = 0;
        for (int n = samples.size(); index < n; index++) {
            Sample s = samples.get(index);
            if (s.date == date) {
                samples.set(index, new Sample(date, rankFromOldest));
                return;
            }
            if (s.date > date) {
                break;
            }
        }
        samples.add(index, new Sample(date, rankFromOldest));
        // keep ranks monotonic: a later sample can never count fewer messages than an earlier one
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).rankFromOldest < samples.get(i - 1).rankFromOldest) {
                samples.set(i, new Sample(samples.get(i).date, samples.get(i - 1).rankFromOldest));
            }
        }
    }
}
