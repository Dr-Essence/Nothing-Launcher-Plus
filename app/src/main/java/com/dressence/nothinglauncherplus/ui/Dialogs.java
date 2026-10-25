package com.dressence.nothinglauncherplus.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

/** Small dark dialogs shared by the settings screens. */
final class Dialogs {

    interface TextCallback {
        void onText(String text);
    }

    private Dialogs() {
    }

    static AlertDialog.Builder builder(Context context) {
        return new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    static void promptText(Context context, String title, String initial, String hint,
            TextCallback callback) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint(hint);
        input.setText(initial);
        input.setSelection(initial.length());
        FrameLayout box = new FrameLayout(context);
        int pad = Ui.dp(context, 24);
        box.setPadding(pad, Ui.dp(context, 8), pad, 0);
        box.addView(input);
        builder(context)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        callback.onText(text);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
