package se.lublin.mumla.service;

import java.util.List;

import se.lublin.humla.IHumlaService;

/**
 * Created by andrew on 28/02/17.
 */
public interface IMumlaService extends IHumlaService {
    void setOverlayShown(boolean showOverlay);

    boolean isOverlayShown();

    void clearChatNotifications();

    void markErrorShown();

    boolean isErrorShown();

    void onTalkKeyDown();

    void onTalkKeyUp();

    /** IW2DPO 19-11-2026-1: TX TIMEOUT popup listener (called on the main thread). */
    interface TxTimeoutListener {
        void onTxTimeoutChanged(boolean active);
    }

    /** IW2DPO: @return true after a TX TIMEOUT, until any key is pressed. */
    boolean isTxTimeoutActive();

    /** IW2DPO: any key pressed: the TX TIMEOUT popup is closed, PTT works again. */
    void clearTxTimeout();

    void setTxTimeoutListener(TxTimeoutListener listener);

    List<IChatMessage> getMessageLog();

    void clearMessageLog();

    void setSuppressNotifications(boolean suppressNotifications);
}
