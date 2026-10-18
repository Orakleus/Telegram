package org.telegram.messenger;

import android.util.LruCache;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;

/**
 * Chronologie — the chronological index of a dialog.
 *
 * Knows, for a dialog, how many messages it holds, when the first and the last
 * were sent, and how many messages were sent before a set of boundary dates
 * (every 1st of January first, then every month of a year on demand). From
 * that it maps a date to a rank in the history and back, which is what the
 * timeline scrubber needs to lay years out proportionally to their content.
 *
 * Everything comes from messages.getHistory with offset_date and limit 1: the
 * response carries count (total messages of the dialog) and offset_id_offset
 * (rank of the returned message counted from the newest), the very calculation
 * ChatActivity already performs to count a day's messages. Exact for every kind
 * of dialog; no new API, no schema change.
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

    private static final int MAX_IN_FLIGHT = 3;
    private static final int OLDEST_PROBE = -1;
    private static final int NEWEST_PROBE = -2;

    private static final LruCache<String, ChatTimelineIndex> cache = new LruCache<>(16);

    public static ChatTimelineIndex get(int currentAccount, long dialogId, int classGuid) {
        String key = currentAccount + "_" + dialogId;
        ChatTimelineIndex index = cache.get(key);
        if (index == null) {
            index = new ChatTimelineIndex(currentAccount, dialogId);
            cache.put(key, index);
        }
        index.attach(classGuid);
        return index;
    }

    public final int currentAccount;
    public final long dialogId;
    private int classGuid;

    public int totalCount = -1;
    public int firstDate;
    public int lastDate;
    public int firstMessageId;
    public int lastMessageId;

    private final ArrayList<Sample> samples = new ArrayList<>();
    private final HashSet<Integer> requested = new HashSet<>();
    private final HashSet<Integer> answered = new HashSet<>();
    private final HashSet<Integer> refinedYears = new HashSet<>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private final Calendar calendar = Calendar.getInstance();

    private int inFlight;
    private boolean pumpScheduled;
    private boolean bootstrapStarted;
    private boolean newestKnown;
    private boolean oldestKnown;
    private boolean yearsEnqueued;
    private long floodWaitUntil;
    private Listener listener;

    private ChatTimelineIndex(int currentAccount, long dialogId) {
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
    }

    /** A (new) fragment uses this index: requests are bound to its guid; unanswered ones are re-queued. */
    public void attach(int classGuid) {
        if (this.classGuid != classGuid) {
            this.classGuid = classGuid;
            inFlight = 0;
            for (Integer date : requested) {
                if (!answered.contains(date) && !queue.contains(date)) {
                    queue.addLast(date);
                }
            }
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
        return inFlight > 0 || !queue.isEmpty();
    }

    public boolean isYearRefined(int year) {
        return refinedYears.contains(year);
    }

    public int getFirstYear() {
        return yearOf(firstDate);
    }

    public int getLastYear() {
        return yearOf(lastDate);
    }

    /** Starts loading: newest and oldest message, then every 1st of January. Cheap to call again. */
    public void load() {
        if (!bootstrapStarted) {
            bootstrapStarted = true;
            queue.addFirst(NEWEST_PROBE);
            queue.addLast(OLDEST_PROBE);
        } else if (isReady() && !yearsEnqueued) {
            enqueueYears();
        }
        pump();
    }

    /** Asks for the month boundaries of a year (eleven requests), ahead of everything else in the queue. */
    public void refineYear(int year) {
        if (!isReady() || refinedYears.contains(year) || year < getFirstYear() || year > getLastYear()) {
            return;
        }
        refinedYears.add(year);
        for (int month = Calendar.DECEMBER; month >= Calendar.FEBRUARY; month--) {
            int date = startOf(year, month);
            if (date > firstDate && date <= lastDate && !requested.contains(date)) {
                queue.remove(date);
                queue.addFirst(date);
            }
        }
        pump();
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

    /** Messages sent in a month ({@code month} as in Calendar, 0-based). */
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
        yearsEnqueued = true;
        int first = getFirstYear();
        int last = getLastYear();
        for (int year = first + 1; year <= last; year++) {
            int date = startOf(year, Calendar.JANUARY);
            if (!requested.contains(date)) {
                queue.addLast(date);
            }
        }
    }

    private void pump() {
        if (System.currentTimeMillis() < floodWaitUntil) {
            if (!pumpScheduled) {
                pumpScheduled = true;
                long delay = floodWaitUntil - System.currentTimeMillis() + 50;
                AndroidUtilities.runOnUIThread(() -> {
                    pumpScheduled = false;
                    pump();
                }, delay);
            }
            return;
        }
        while (inFlight < MAX_IN_FLIGHT && !queue.isEmpty()) {
            int date = queue.pollFirst();
            if (date >= 0 && answered.contains(date)) {
                continue;
            }
            requested.add(date);
            send(date);
        }
    }

    private void send(final int date) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
        req.limit = 1;
        if (date == NEWEST_PROBE) {
            req.offset_date = 0;
        } else if (date == OLDEST_PROBE) {
            req.offset_date = 1;
            req.add_offset = -1;
        } else {
            req.offset_date = date;
        }
        inFlight++;
        int reqId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            inFlight = Math.max(0, inFlight - 1);
            handle(date, response, error);
            pump();
        }));
        ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(reqId, classGuid);
    }

    private void handle(int date, TLObject response, TLRPC.TL_error error) {
        if (error != null) {
            if (error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
                int seconds = 5;
                try {
                    seconds = Integer.parseInt(error.text.substring("FLOOD_WAIT_".length()));
                } catch (Exception ignore) {}
                floodWaitUntil = System.currentTimeMillis() + seconds * 1000L;
                queue.addFirst(date);
            } else {
                FileLog.d("ChatTimelineIndex: request for " + date + " failed: " + error.text);
                requested.remove(date);
            }
            return;
        }
        if (!(response instanceof TLRPC.messages_Messages)) {
            requested.remove(date);
            return;
        }
        TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
        MessagesController.getInstance(currentAccount).putUsers(res.users, false);
        MessagesController.getInstance(currentAccount).putChats(res.chats, false);
        answered.add(date);

        boolean hasCount = !(res instanceof TLRPC.TL_messages_messages);
        TLRPC.Message message = res.messages.isEmpty() ? null : res.messages.get(0);

        if (date == NEWEST_PROBE) {
            if (message == null) {
                totalCount = 0;
                newestKnown = true;
            } else {
                totalCount = hasCount ? res.count : res.messages.size();
                lastDate = message.date;
                lastMessageId = message.id;
                newestKnown = true;
                FileLog.d("ChatTimelineIndex: newest message date=" + lastDate + " total=" + totalCount + " offset=" + res.offset_id_offset + " hasCount=" + hasCount);
            }
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

        if (isReady() && !yearsEnqueued) {
            enqueueYears();
        }
        if (listener != null) {
            listener.onIndexUpdated(this);
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
