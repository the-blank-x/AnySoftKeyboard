package com.anysoftkeyboard.ime;

import android.content.DialogInterface;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import com.anysoftkeyboard.api.KeyCodes;
import com.anysoftkeyboard.devicespecific.Clipboard;
import com.anysoftkeyboard.keyboards.Keyboard;
import com.menny.android.anysoftkeyboard.AnyApplication;
import com.menny.android.anysoftkeyboard.R;
import java.util.ArrayList;
import java.util.List;
import net.evendanan.pixel.GeneralDialogController;

public abstract class AnySoftKeyboardClipboard extends AnySoftKeyboardSwipeListener {

    private boolean mArrowSelectionState;
    private Clipboard mClipboard;
    protected static final int MAX_CHARS_PER_CODE_POINT = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        mClipboard = AnyApplication.getDeviceSpecific().createClipboard(getApplicationContext());
    }

    private void showAllClipboardEntries(final Keyboard.Key key) {
        if (mClipboard.getClipboardEntriesCount() == 0) {
            showToastMessage(R.string.clipboard_is_empty_toast, true);
        } else {
            final List<CharSequence> nonEmpties =
                    new ArrayList<>(mClipboard.getClipboardEntriesCount());
            for (int entryIndex = 0;
                    entryIndex < mClipboard.getClipboardEntriesCount();
                    entryIndex++) {
                nonEmpties.add(mClipboard.getText(entryIndex));
            }
            final CharSequence[] entries = nonEmpties.toArray(new CharSequence[0]);
            DialogInterface.OnClickListener onClickListener =
                    (dialog, which) -> onText(key, entries[which]);
            showOptionsDialogWithData(
                    R.string.clipboard_paste_entries_title,
                    R.drawable.ic_clipboard_paste_light,
                    new CharSequence[0],
                    onClickListener,
                    new GeneralDialogController.DialogPresenter() {
                        @Override
                        public void beforeDialogShown(
                                @NonNull AlertDialog dialog, @Nullable Object data) {}

                        @Override
                        public void onSetupDialogRequired(
                                AlertDialog.Builder builder, int optionId, @Nullable Object data) {
                            builder.setAdapter(
                                    new ClipboardEntriesAdapter(
                                            AnySoftKeyboardClipboard.this, entries),
                                    onClickListener);
                        }
                    });
        }
    }

    protected void handleClipboardOperation(
            final Keyboard.Key key, final int primaryCode, InputConnection ic) {
        abortCorrectionAndResetPredictionState(false);
        switch (primaryCode) {
            case KeyCodes.CLIPBOARD_PASTE:
                CharSequence clipboardText =
                        mClipboard.getClipboardEntriesCount() > 0
                                ? mClipboard.getText(0 /*last entry paste*/)
                                : "";
                if (!TextUtils.isEmpty(clipboardText)) {
                    onText(key, clipboardText);
                } else {
                    showToastMessage(R.string.clipboard_is_empty_toast, true);
                }
                break;
            case KeyCodes.CLIPBOARD_CUT:
            case KeyCodes.CLIPBOARD_COPY:
                if (ic != null) {
                    CharSequence selectedText =
                            ic.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES);
                    if (!TextUtils.isEmpty(selectedText)) {
                        mClipboard.setText(selectedText);
                        if (primaryCode == KeyCodes.CLIPBOARD_CUT) {
                            // sending a DEL key will delete the selected text
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                        } else {
                            // showing toast, since there isn't any other UI feedback
                            showToastMessage(R.string.clipboard_copy_done_toast, true);
                        }
                    }
                }
                break;
            case KeyCodes.CLIPBOARD_SELECT_ALL:
                final CharSequence toLeft = ic.getTextBeforeCursor(10240, 0);
                final CharSequence toRight = ic.getTextAfterCursor(10240, 0);
                final int leftLength = toLeft == null ? 0 : toLeft.length();
                final int rightLength = toRight == null ? 0 : toRight.length();
                if (leftLength != 0 || rightLength != 0) {
                    ic.setSelection(0, leftLength + rightLength);
                }
                break;
            case KeyCodes.CLIPBOARD_PASTE_POPUP:
                showAllClipboardEntries(key);
                break;
            case KeyCodes.CLIPBOARD_SELECT:
                mArrowSelectionState = !mArrowSelectionState;
                if (mArrowSelectionState) {
                    showToastMessage(R.string.clipboard_fine_select_enabled_toast, true);
                }
                break;
            case KeyCodes.UNDO:
                sendDownUpKeyEvents(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON);
                break;
            case KeyCodes.REDO:
                sendDownUpKeyEvents(
                        KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
                break;
            default:
                throw new IllegalArgumentException(
                        "The keycode "
                                + primaryCode
                                + " is not covered by handleClipboardOperation!");
        }
    }

    protected boolean handleSelectionExpending(int keyEventKeyCode, InputConnection ic) {
        if (mArrowSelectionState && ic != null) {
            final int selectionEnd = getCursorPosition();
            final int selectionStart = mGlobalSelectionStartPositionDangerous;
            markExpectingSelectionUpdate();
            switch (keyEventKeyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    // A Unicode code-point can be made up of two Java chars.
                    // We check if that's what happening before the cursor:
                    final String toLeft =
                            ic.getTextBeforeCursor(MAX_CHARS_PER_CODEPOINT, 0).toString();
                    if (toLeft.length() == 0) {
                        ic.setSelection(selectionStart, selectionEnd);
                    } else {
                        ic.setSelection(
                                selectionStart
                                        - Character.charCount(
                                                toLeft.codePointBefore(toLeft.length())),
                                selectionEnd);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    final String toRight =
                            ic.getTextAfterCursor(MAX_CHARS_PER_CODEPOINT, 0).toString();
                    if (toRight.length() == 0) {
                        ic.setSelection(selectionStart, selectionEnd);
                    } else {
                        ic.setSelection(
                                selectionStart,
                                selectionEnd + Character.charCount(toRight.codePointAt(0)));
                    }
                    return true;
                default:
                    mArrowSelectionState = false;
            }
        }
        return false;
    }

    @RequiresApi(Build.VERSION_CODES.HONEYCOMB)
    public void sendDownUpKeyEvents(int keyEventCode, int metaState) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long eventTime = SystemClock.uptimeMillis();
        ic.sendKeyEvent(
                new KeyEvent(
                        eventTime,
                        eventTime,
                        KeyEvent.ACTION_DOWN,
                        keyEventCode,
                        0,
                        metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
        ic.sendKeyEvent(
                new KeyEvent(
                        eventTime,
                        SystemClock.uptimeMillis(),
                        KeyEvent.ACTION_UP,
                        keyEventCode,
                        0,
                        metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }

    @Override
    public void onPress(int primaryCode) {
        if (mArrowSelectionState
                && (primaryCode != KeyCodes.ARROW_LEFT && primaryCode != KeyCodes.ARROW_RIGHT)) {
            mArrowSelectionState = false;
        }
    }

    private class ClipboardEntriesAdapter extends ArrayAdapter<CharSequence> {
        public ClipboardEntriesAdapter(@NonNull Context context, CharSequence[] items) {
            super(context, R.layout.clipboard_dialog_entry, R.id.clipboard_entry_text, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            View deleteView = view.findViewById(R.id.clipboard_entry_delete);
            deleteView.setTag(R.id.clipboard_entry_delete, position);
            deleteView.setOnClickListener(this::onItemDeleteClicked);

            return view;
        }

        private void onItemDeleteClicked(View view) {
            int position = (int) view.getTag(R.id.clipboard_entry_delete);
            mClipboard.deleteEntry(position);
            closeGeneralOptionsDialog();
        }
    }
}
