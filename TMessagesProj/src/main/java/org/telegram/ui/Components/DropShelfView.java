package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ShareDialogCell;

import java.util.ArrayList;

/**
 * The shelf of "Drag to forward": a sheet that rises from the bottom of the chat while a
 * message is being dragged, showing the chats the message can be dropped on. It reuses the
 * cells of the forward sheet ({@link ShareDialogCell}) and lists the chats in the same order
 * (Saved Messages first, then the most recent chats one can write to). It never receives touch
 * events itself: the drag controller tells it which point is hovered and which chat was hit.
 */
public class DropShelfView extends FrameLayout {

    public static final int COLUMNS = 4;
    public static final int ROWS = 2;
    private static final int CELL_HEIGHT = 103; // forced by ShareDialogCell.onMeasure for TYPE_SHARE
    private static final int HANDLE_AREA = 12;
    private static final int CORNER_RADIUS = 18;
    private static final float HOVER_SCALE = 1.08f;

    private final int currentAccount = UserConfig.selectedAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final RecyclerListView gridView;
    private final Adapter adapter;
    private final ArrayList<Long> dialogIds = new ArrayList<>();

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long hoveredDialogId;
    private int bottomInset;

    public DropShelfView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setVisibility(INVISIBLE); // laid out while idle, so its position is known when it rises
        setElevation(dp(8));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // the bottom corners are extended below the screen, so only the top ones show
                outline.setRoundRect(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight() + dp(CORNER_RADIUS), dp(CORNER_RADIUS));
            }
        });
        setClipToOutline(false);

        gridView = new RecyclerListView(context, resourcesProvider);
        gridView.setLayoutManager(new GridLayoutManager(context, COLUMNS) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        });
        gridView.setAdapter(adapter = new Adapter(context));
        gridView.setSelectorDrawableColor(0);
        gridView.setOverScrollMode(OVER_SCROLL_NEVER);
        gridView.setClipChildren(false);
        gridView.setClipToPadding(false);
        addView(gridView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, ROWS * CELL_HEIGHT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, HANDLE_AREA, 0, 0));

        updateColors();
    }

    public void updateColors() {
        backgroundPaint.setColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        handlePaint.setColor(Theme.getColor(Theme.key_sheet_scrollUp, resourcesProvider));
        invalidate();
    }

    /** Height of the shelf once risen, bottom inset included. */
    public int getShelfHeight() {
        return dp(HANDLE_AREA + ROWS * CELL_HEIGHT) + bottomInset;
    }

    /** Lists the chats again (the order of recent chats moves) and rises from the bottom. */
    public void show(int bottomInset) {
        this.bottomInset = bottomInset;
        hoveredDialogId = 0;
        fetchDialogs();
        adapter.notifyDataSetChanged();
        final ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height != getShelfHeight()) {
            lp.height = getShelfHeight();
            setLayoutParams(lp);
        }
        animate().cancel();
        setVisibility(VISIBLE);
        setTranslationY(getShelfHeight());
        animate().translationY(0).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    public void hide() {
        setHovered(0);
        animate().cancel();
        animate().translationY(getShelfHeight()).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT).withEndAction(() -> setVisibility(INVISIBLE)).start();
    }

    /** Whether the shelf is up (rising, risen or on its way down). */
    public boolean isRisen() {
        return getVisibility() == VISIBLE;
    }

    /** The chat under a point given in this view's coordinates, or 0. */
    public long dialogAt(float x, float y) {
        if (getVisibility() != VISIBLE || y < 0 || y > getMeasuredHeight()) {
            return 0;
        }
        for (int i = 0; i < gridView.getChildCount(); i++) {
            final View child = gridView.getChildAt(i);
            if (!(child instanceof ShareDialogCell)) {
                continue;
            }
            final float left = gridView.getX() + child.getLeft();
            final float top = gridView.getY() + child.getTop();
            if (x >= left && x < left + child.getWidth() && y >= top && y < top + child.getHeight()) {
                return ((ShareDialogCell) child).getCurrentDialog();
            }
        }
        return 0;
    }

    /** Checks and enlarges the hovered chat, with a tick on each change; 0 clears the hover. */
    public void setHovered(long dialogId) {
        if (hoveredDialogId == dialogId) {
            return;
        }
        final ShareDialogCell previous = cellFor(hoveredDialogId);
        if (previous != null) {
            previous.setChecked(false, true);
            previous.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        }
        hoveredDialogId = dialogId;
        final ShareDialogCell cell = cellFor(dialogId);
        if (cell != null) {
            cell.setChecked(true, true);
            cell.animate().scaleX(HOVER_SCALE).scaleY(HOVER_SCALE).setDuration(160).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    }

    public long getHoveredDialogId() {
        return hoveredDialogId;
    }

    /** Center of the avatar of a listed chat, in this view's coordinates; false if not listed. */
    public boolean avatarCenter(long dialogId, float[] out) {
        final ShareDialogCell cell = cellFor(dialogId);
        if (cell == null) {
            return false;
        }
        out[0] = gridView.getX() + cell.getLeft() + cell.getWidth() / 2f;
        out[1] = gridView.getY() + cell.getTop() + dp(7 + 28); // avatar: 56 dp, 7 dp below the cell top
        return true;
    }

    private ShareDialogCell cellFor(long dialogId) {
        if (dialogId == 0) {
            return null;
        }
        for (int i = 0; i < gridView.getChildCount(); i++) {
            final View child = gridView.getChildAt(i);
            if (child instanceof ShareDialogCell && ((ShareDialogCell) child).getCurrentDialog() == dialogId) {
                return (ShareDialogCell) child;
            }
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight() + dp(CORNER_RADIUS));
        canvas.drawRoundRect(rect, dp(CORNER_RADIUS), dp(CORNER_RADIUS), backgroundPaint);
        final float cx = getMeasuredWidth() / 2f;
        rect.set(cx - dp(18), dp(6), cx + dp(18), dp(10));
        canvas.drawRoundRect(rect, dp(2), dp(2), handlePaint);
        super.onDraw(canvas);
    }

    /**
     * The order of the forward sheet (ShareAlert.ShareDialogsAdapter.fetchDialogs): Saved Messages,
     * then the recent chats one can write to, archived ones last. Forums and monoforums (a topic
     * would have to be chosen), deleted users, paid and premium-blocked chats are left out, and the
     * list stops at the number of cells the shelf shows.
     */
    private void fetchDialogs() {
        dialogIds.clear();
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final long selfUserId = UserConfig.getInstance(currentAccount).clientUserId;
        final int max = COLUMNS * ROWS;
        if (!controller.dialogsForward.isEmpty()) {
            dialogIds.add(controller.dialogsForward.get(0).id);
        } else {
            dialogIds.add(selfUserId);
        }
        final ArrayList<Long> archived = new ArrayList<>();
        final ArrayList<TLRPC.Dialog> allDialogs = controller.getAllDialogs();
        for (int a = 0; a < allDialogs.size() && dialogIds.size() < max; a++) {
            final TLRPC.Dialog dialog = allDialogs.get(a);
            if (!(dialog instanceof TLRPC.TL_dialog) || dialog.id == selfUserId || DialogObject.isEncryptedDialog(dialog.id)) {
                continue;
            }
            if (DialogObject.isUserDialog(dialog.id)) {
                final TLRPC.User user = controller.getUser(dialog.id);
                if (user == null || UserObject.isDeleted(user) || UserObject.isService(user.id)) {
                    continue;
                }
                final TL_account.RequirementToContact r = controller.isUserContactBlocked(dialog.id);
                if (DialogObject.isPremiumBlocked(r) || DialogObject.getMessagesStarsPrice(r) > 0) {
                    continue;
                }
            } else {
                final TLRPC.Chat chat = controller.getChat(-dialog.id);
                if (chat == null || chat.forum || chat.monoforum || ChatObject.isNotInChat(chat)
                        || chat.gigagroup && !ChatObject.hasAdminRights(chat)
                        || ChatObject.isChannel(chat) && !chat.creator && (chat.admin_rights == null || !chat.admin_rights.post_messages) && !chat.megagroup) {
                    continue;
                }
            }
            if (controller.getSendPaidMessagesStars(dialog.id) > 0) {
                continue;
            }
            if (dialog.folder_id == 1) {
                archived.add(dialog.id);
            } else {
                dialogIds.add(dialog.id);
            }
        }
        for (int a = 0; a < archived.size() && dialogIds.size() < max; a++) {
            dialogIds.add(archived.get(a));
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return dialogIds.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final ShareDialogCell cell = new ShareDialogCell(context, ShareDialogCell.TYPE_SHARE, resourcesProvider);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, dp(CELL_HEIGHT)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final ShareDialogCell cell = (ShareDialogCell) holder.itemView;
            final long id = dialogIds.get(position);
            cell.setScaleX(1f);
            cell.setScaleY(1f);
            cell.setTopic(null, false);
            cell.setDialog(id, id == hoveredDialogId, null);
        }
    }
}
