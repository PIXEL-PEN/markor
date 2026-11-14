/*#######################################################
 *
 *   Autumn
 *   Copyright (C) 2026 PIXEL-PEN
 *   Licensed under the GNU General Public License v3.0
 *
#########################################################*/
package net.pixelpen.journal.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import net.pixelpen.journal.R;
import net.pixelpen.journal.activity.QuicknoteEntryActivity;

/**
 * The Quicknote widget, two cells: a cream bar reading "Quicknote…" with the Quicknote bolt
 * on a navy tile at its end.
 *
 * <p>A home-screen widget cannot hold a text field (RemoteViews have no EditText), so a tap
 * opens {@link QuicknoteEntryActivity} across the top of the screen, where the typing happens. Nothing here changes after it is placed, so there is no
 * update schedule.
 */
public class QuicknoteWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(final Context context, final AppWidgetManager manager, final int[] ids) {
        final Intent open = new Intent(context, QuicknoteEntryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (final int id : ids) {
            final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quicknote);
            views.setOnClickPendingIntent(R.id.qn_widget_root, pi);
            views.setOnClickPendingIntent(R.id.qn_widget_field, pi);
            views.setOnClickPendingIntent(R.id.qn_widget_logo, pi);
            manager.updateAppWidget(id, views);
        }
    }
}
