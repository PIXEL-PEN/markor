package net.pixelpen.journal.activity.openeditor;

import android.content.Intent;
import android.os.Bundle;

import net.pixelpen.journal.activity.DocumentActivity;
import net.pixelpen.journal.activity.MarkorBaseActivity;

/**
 * This Activity exists solely to launch DocumentActivity with the correct intent
 * it is necessary as widget and shortcut intents do not respect MultipleTask etc
 */
public class OpenFromShortcutOrWidgetActivity extends MarkorBaseActivity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchActivityAndFinish(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        launchActivityAndFinish(intent);
    }

    private void launchActivityAndFinish(Intent intent) {
        finish();
        DocumentActivity.launch(this, intent);
    }
}