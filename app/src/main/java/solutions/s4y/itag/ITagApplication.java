package solutions.s4y.itag;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.google.firebase.analytics.FirebaseAnalytics;

public final class ITagApplication extends Application {
    private final static String LT = ITagApplication.class.getName();
    // context is not used outside Application so there's a hope there will be no memory leak
    @SuppressLint("StaticFieldLeak")
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = this;
    }

    static public void handleError(Throwable th) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (context == null) {
                Log.e(LT, "Attempt to handle error before application created", th);
                Crashlytics.logException(th);
            } else {
                Log.e(LT, "Toasted", th);
                Toast.makeText(context, th.getMessage(), Toast.LENGTH_LONG).show();
                Crashlytics.logException(th);
            }
        });
    }

    private static FirebaseAnalytics sFirebaseAnalytics;

    static public void fa(final String event, Bundle bundle) {
        if (context == null) return;
        if (sFirebaseAnalytics == null) {
            sFirebaseAnalytics = FirebaseAnalytics.getInstance(context);
            faAppCreated();
        }
        sFirebaseAnalytics.logEvent(event, bundle);
        if (BuildConfig.DEBUG) {
            Log.d(LT, "FA log " + event);
        }
    }

    static public void fa(final String event) {
        fa(event, null);
    }

    static public void faAppCreated() {
        fa("itag_app_created");
    }

    static public void faNoBluetooth() {
        fa("itag_no_bluetooth");
    }

    static public void faBluetoothDisable() {
        fa("itag_bluetooth_disable");
    }

    static public void faNotITag() {
        fa("itag_not_itag");
    }

    static public void faScanView(boolean empty) {
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, "itag_scan_view_is_empty");
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "First Scan");
        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "boolean");
        bundle.putBoolean(FirebaseAnalytics.Param.VALUE, empty);
        fa("itag_scan_view", bundle);
    }

    static public void faITagsView(int devices) {
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, "itag_itags_view_device");
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "Remembered Devices");
        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "int");
        bundle.putInt(FirebaseAnalytics.Param.VALUE, devices);
        fa("itag_itags_view");
    }

    static public void faRememberITag() {
        fa("itag_remember_itag");
    }

    static public void faForgetITag() {
        fa("itag_forget_itag");
    }

    static public void faNameITag() {
        fa("itag_set_name");
    }

    static public void faColorITag() {
        fa("itag_set_color");
    }

    static public void faMuteTag() {
        fa("itag_mute");
    }

    static public void faUnmuteTag() {
        fa("itag_unmute");
    }

    static public void faFindITag() {
        fa("itag_find_itag");
    }

    static public void faITagFound() {
        fa("itag_itag_found");
    }

    static public void faITagLost(boolean error) {
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.ITEM_ID, "itag_itag_lost_error");
        bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "Lost with error");
        bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "boolean");
        bundle.putBoolean(FirebaseAnalytics.Param.VALUE, error);
        fa("itag_itag_lost");
    }

    static public void faFindPhone() {
        fa("itag_find_phone");
    }

    static public void faITagDisconnected() {
        fa("itag_user_disconnect");
    }

    static public void faITagConnected() {
        fa("itag_user_connect");
    }
}
