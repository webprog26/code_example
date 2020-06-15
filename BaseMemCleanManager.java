package name.rocketshield.chromium.features.cleaner;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.widget.RemoteViews;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.browser.document.ChromeLauncherActivity;

import java.util.Calendar;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
abstract class BaseMemCleanManager {

    protected final RocketMemClean.Type type;
    protected final RocketRemoteConfig remoteConfig;

    private final int mNotificationId;
    private final AlarmManagerWrapper alarmManagerWrapper;
    private final String mNotificationRequestTag;

    protected Storage mStorage;

    private boolean mIsDeferredExecution;

    BaseMemCleanManager(RocketMemClean.Type type) {
        this.type = type;
        mNotificationId = getClass().getSimpleName().hashCode();
        alarmManagerWrapper = new AlarmManagerWrapper(getApplicationContext());
        mStorage = new RocketMemCleanerStorage(getApplicationContext());
        remoteConfig = RocketRemoteConfig.getInstance();
        this.mNotificationRequestTag = getClass().getName();
    }

    @NonNull
    protected static Context getApplicationContext() {
        return ContextUtils.getApplicationContext();
    }

    @NonNull
    protected static Calendar getCalendar() {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        return calendar;
    }

    protected abstract boolean isNotificationsEnabledFromRemote();

    @NonNull
    protected abstract String getBroadcastIntentAction();

    @NonNull
    protected abstract String getCleanerActivityIntentAction();

    @NonNull
    protected abstract Class<? extends Activity> getCleanerActivityClass();

    protected boolean isNotificationAllowed() {
        return isNotificationsEnabledFromRemote() && mStorage.isNotificationEnabledByUser(type);
    }

    @NonNull
    protected abstract RemoteViews getContentView();

    protected abstract long getNewNotificationScheduleTime();

    @NonNull
    protected abstract String getNotificationScheduleTimeKey();

    @NonNull
    protected abstract Class<? extends BroadcastReceiver> getReceiverClass();

    protected abstract void onNotificationShown();

    @IdRes
    protected abstract int getContentViewControlId();

    @Nullable
    protected Runnable getOnIntentHandledRunnable() {
        return null;
    }

    void onBroadcastReceived(Context context, @Nullable final String action) {
        remoteConfig.doNowOrAfterUpdate(() -> {
            if (isNotificationAllowed()) {
                if (isCleanerNotificationAction(action)) {
                    sendNotification(context);
                }
                rescheduleNotification(false);
            } else {
                rescheduleNotification(true);
            }
        });
    }

    final void updateNotifications(Context context, final boolean appIsInForeground) {
        if (!appIsInForeground && isNotificationAllowed()) {
            scheduleNotificationInternal();
        } else {
            alarmManagerWrapper.cancelTaskByTag(mNotificationRequestTag);
            hideNotification(context);
        }
    }

    void scheduleNotificationInternal() {
        rescheduleNotification(false);
    }

    void handleActivityIntent(@NonNull final Activity activity) {
        if (activity != null) {
            final Intent incomingIntent = activity.getIntent();
            if (incomingIntent != null) {
                String intentAction = incomingIntent.getAction();
                if (getCleanerActivityIntentAction().equals(intentAction)) {
                    final Intent cleanerIntent = new Intent(activity, getCleanerActivityClass());
                    hideNotification(activity);
                    activity.startActivity(cleanerIntent);
                    incomingIntent.setAction("");
                    final Runnable onIntentHandledRunnable = getOnIntentHandledRunnable();
                    if (onIntentHandledRunnable != null) {
                        onIntentHandledRunnable.run();
                    }
                }
            }
        }
    }

    boolean isDeferredExecution() {
        return mIsDeferredExecution;
    }

    void setDeferredExecution(final boolean isDeferredExecution) {
        this.mIsDeferredExecution = isDeferredExecution;
    }

    private boolean isCleanerNotificationAction(@Nullable final String action) {
        return action.equals(this.getBroadcastIntentAction());
    }

    private void sendNotification(Context context) {
        final RemoteViews contentView = getContentView();
        if (contentView != null) {
            contentView.setOnClickPendingIntent(getContentViewControlId(),
                    getActivityPendingIntent(getCleanerActivityIntentAction()));
            new NotificationsHelper(context).sendNotification(getActivityPendingIntent(null)
                    , mNotificationId, contentView);
            onNotificationShown();
        }
    }

    private void rescheduleNotification(final boolean isReboot) {
        final PendingIntent broadcastPendingIntent
                = getBroadcastPendingIntent(getReceiverClass(), this.getBroadcastIntentAction());
        alarmManagerWrapper.enqueue(
                new AlarmManagerWrapper.Request(mNotificationRequestTag,
                        broadcastPendingIntent,
                        getScheduleTimeMillis(isReboot)));
    }

    private void hideNotification(Context context) {
        new NotificationsHelper(context).cancelNotification(mNotificationId);
    }

    @NonNull
    private PendingIntent getBroadcastPendingIntent(@NonNull final Class<? extends BroadcastReceiver> cls,
                                                    @Nullable final String action) {
        final Intent intent = new Intent(getApplicationContext(), cls);
        if (action != null) {
            intent.setAction(action);
        }
        return PendingIntent.getBroadcast(getApplicationContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @NonNull
    private PendingIntent getActivityPendingIntent(@Nullable final String action) {
        final Intent intent = new Intent(getApplicationContext(), ChromeLauncherActivity.class);
        if (action != null) {
            intent.setAction(action);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(getApplicationContext(),
                0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private long getScheduleTimeMillis(boolean isReboot) {
        final String key = getNotificationScheduleTimeKey();
        final long lastNotificationScheduleTime = mStorage.getNotificationScheduleTime(key);
        if (isReboot && lastNotificationScheduleTime > System.currentTimeMillis()) {
            return lastNotificationScheduleTime;
        }
        final long newScheduleTime = getNewNotificationScheduleTime();
        mStorage.saveNotificationScheduleTime(key, newScheduleTime);
        return newScheduleTime;
    }

    interface Storage {
        void saveNotificationWasShownTimes(final int number);

        int getNotificationWasShownTimes();

        long getNotificationScheduleTime(final String key);

        void saveNotificationScheduleTime(final String key, final long newTime);

        boolean isNotificationEnabledByUser(RocketMemClean.Type type);
    }
}

