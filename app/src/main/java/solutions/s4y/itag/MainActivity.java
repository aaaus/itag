package solutions.s4y.itag;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import solutions.s4y.itag.ble.ITagGatt;
import solutions.s4y.itag.ble.ITagsDb;
import solutions.s4y.itag.ble.ITagDevice;
import solutions.s4y.itag.ble.ITagsService;
import solutions.s4y.itag.ble.LeScanResult;
import solutions.s4y.itag.ble.LeScanner;

public class MainActivity extends Activity implements LeScanner.LeScannerListener, ITagsDb.DbListener {
    static public final int REQUEST_ENABLE_LOCATION = 2;
    public BluetoothAdapter mBluetoothAdapter;
    public ITagsService mITagsService;
    public boolean mITagsServiceBound;

    public interface ServiceBoundListener {
        void onBoundingChanged(@NonNull final MainActivity activity);
    }

    private static final List<ServiceBoundListener> mServiceBoundListeners =
            new ArrayList<>(2);

    public static void addServiceBoundListener(ServiceBoundListener listener) {
        if (BuildConfig.DEBUG) {
            if (mServiceBoundListeners.contains(listener)) {
                ITagApplication.handleError(new Error("Add duplicate ITagChangeListener listener"));
            }
        }
        mServiceBoundListeners.add(listener);
    }

    public static void removeServiceBoundListener(ServiceBoundListener listener) {
        if (BuildConfig.DEBUG) {
            if (!mServiceBoundListeners.contains(listener)) {
                ITagApplication.handleError(new Error("Remove nonexisting ITagChangeListener listener"));
            }
        }
        mServiceBoundListeners.remove(listener);
    }

    private void notifyServiceBoundChanged() {
        for (ServiceBoundListener listener : mServiceBoundListeners) {
            listener.onBoundingChanged(this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        /*
        // ATTENTION: This was auto-generated to handle app links.
        Intent appLinkIntent = getIntent();
        String appLinkAction = appLinkIntent.getAction();
        Uri appLinkData = appLinkIntent.getData();
        */
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null)
            mBluetoothAdapter = bluetoothManager.getAdapter();

    }

    private void setupProgressBar() {
        ProgressBar pb = findViewById(R.id.progress);
        if (LeScanner.isScanning) {
            pb.setVisibility(View.VISIBLE);
            pb.setIndeterminate(false);
            pb.setMax(LeScanner.TIMEOUT);
            pb.setProgress(LeScanner.tick);
        } else {
            pb.setVisibility(View.GONE);
        }
    }


    private enum FragmentType {
        OTHER,
        ITAGS,
        SCANNER
    }

    private FragmentType mSelectedFragment;

    private int mEnableAttempts = 0;

    private void setupContent() {
        final FragmentManager fragmentManager = getFragmentManager();
        final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        Fragment fragment = null;
        if (LeScanner.isScanning) {
            setupProgressBar();
            mEnableAttempts = 0;
            if (mSelectedFragment != FragmentType.SCANNER) {
                fragment = new LeScanFragment();
                mSelectedFragment = FragmentType.SCANNER;
            }
        } else {
            setupProgressBar();
            if (mBluetoothAdapter == null) {
                fragment = new NoBLEFragment();
                mSelectedFragment = FragmentType.OTHER;
            } else {
                if (mBluetoothAdapter.isEnabled()) {
                    mEnableAttempts = 0;
                    if (mSelectedFragment != FragmentType.ITAGS) {
                        fragment = new ITagsFragment();
                        mSelectedFragment = FragmentType.ITAGS;
                    }
                } else {
                    if (mEnableAttempts < 3) {
                        mBluetoothAdapter.enable();
                        mEnableAttempts++;
                        if (mEnableAttempts==1) {
                            Toast.makeText(this, R.string.try_enable_bt,Toast.LENGTH_LONG).show();
                        }
                        setupContent();
                    }else {
                        fragment = new DisabledBLEFragment();
                        mSelectedFragment = FragmentType.OTHER;
                    }
                }
            }
        }
        if (fragment != null) {
            fragmentTransaction.replace(R.id.content, fragment);
            fragmentTransaction.commit();
        }
    }

    @Override
    public void onBackPressed() {
        if (LeScanner.isScanning && mBluetoothAdapter != null) {
            LeScanner.stopScan(mBluetoothAdapter);
        } else {
            super.onBackPressed();// your code.
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            mITagsService = ((ITagsService.GattBinder) binder).getService();
            mITagsServiceBound = true;
            mITagsService.connect();
            mITagsService.removeFromForeground();
            setupContent();
            notifyServiceBoundChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mITagsServiceBound = false;
            notifyServiceBoundChanged();
        }
    };

    private boolean mIsServiceStartedUnbind;
    @Override
    protected void onResume() {
        super.onResume();
        setupContent();
        Intent intent = new Intent(this, ITagsService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        LeScanner.addListener(this);
        ITagsDb.addListener(this);
        // run the service on the activity start if there are remebered itags
        mIsServiceStartedUnbind=ITagsService.start(this);
    }

    @Override
    protected void onPause() {
        if (ITagsDb.getDevices(this).size() > 0) {
            if (!mIsServiceStartedUnbind) {
                ITagsService.start(this, true);
            }else {
                if (mITagsServiceBound) {
                    mITagsService.addToForeground();
                }
            }
        } else {
            ITagsService.stop(this);
        }
        unbindService(mConnection);
        ITagsDb.removeListener(this);
        LeScanner.removeListener(this);
        super.onPause();
    }

    public void onRemember(View sender) {
        BluetoothDevice device = (BluetoothDevice) sender.getTag();
        if (device == null) {
            ITagApplication.handleError(new Exception("No BLE device"));
            return;
        }
        if (!ITagsDb.has(device)) {
            if (BuildConfig.DEBUG) {
                if (mBluetoothAdapter == null) {
                    ITagApplication.handleError(new Exception("onRemember mBluetoothAdapter=null"));
                    return;
                }
            }
            ITagsDb.remember(this, device);
            LeScanner.stopScan(mBluetoothAdapter);
        }
    }

    public void onForget(View sender) {
        ITagDevice device = (ITagDevice) sender.getTag();
        if (device == null) {
            ITagApplication.handleError(new Exception("No device"));
            return;
        }
        if (ITagsDb.has(device)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.confirm_forget)
                    .setTitle(R.string.confirm_title)
                    .setPositiveButton(android.R.string.yes, (dialog, which) -> ITagsDb.forget(this, device))
                    .setNegativeButton(android.R.string.no, (dialog, which) -> dialog.cancel())
                    .show();
        }
    }

    public void onITagClick(View sender) {
        if (!mITagsServiceBound)
            return;
        ITagDevice device = (ITagDevice) sender.getTag();
        if (device == null) {
            ITagApplication.handleError(new Exception("No device"));
            return;
        }
        ITagGatt gatt = mITagsService.getGatt(device.addr, true);
        boolean needNotify=true;
        if (gatt.isFindingITag()) {
            gatt.stopFindITag();
            needNotify=false;
        }
        if (mITagsServiceBound && mITagsService.isSound()){
            mITagsService.stopSound();
        }
        if (needNotify ){
            Toast.makeText(this, R.string.help_longpress, Toast.LENGTH_SHORT).show();
        }
    }

    public void onStartStopScan(View ignored) {
        if (BuildConfig.DEBUG) {
            if (mBluetoothAdapter == null) {
                ITagApplication.handleError(new Exception("onStartStopScan mBluetoothAdapter=null"));
                return;
            }
        }

        if (LeScanner.isScanning) {
            LeScanner.stopScan(mBluetoothAdapter);
        } else {
            LeScanner.startScan(mBluetoothAdapter, this);
        }
    }

    public void onChangeColor(View sender) {
        final ITagDevice device = (ITagDevice) sender.getTag();
        if (device == null) {
            ITagApplication.handleError(new Exception("No device"));
            return;
        }
        final PopupMenu popupMenu = new PopupMenu(this, sender);
        popupMenu.inflate(R.menu.color);
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case R.id.black:
                    device.color = ITagDevice.Color.BLACK;
                    break;
                case R.id.white:
                    device.color = ITagDevice.Color.WHITE;
                    break;
                case R.id.red:
                    device.color = ITagDevice.Color.RED;
                    break;
                case R.id.green:
                    device.color = ITagDevice.Color.GREEN;
                    break;
                case R.id.blue:
                    device.color = ITagDevice.Color.BLUE;
                    break;
            }
            ITagsDb.save(MainActivity.this);
            ITagsDb.notifyChange();
            ITagApplication.faColorITag();
            return true;
        });
        popupMenu.show();
    }

    public void onLink(View sender) {
        ITagDevice device = (ITagDevice) sender.getTag();
        device.linked=!device.linked;
        ITagsDb.save(MainActivity.this);
        ITagsDb.notifyChange();
        if (device.linked)
            ITagApplication.faUnmuteTag();
        else
            ITagApplication.faMuteTag();
        if (mITagsServiceBound && mITagsService.isSound() && !device.linked){
            mITagsService.stopSound();
        }
    }


    public void onSetName(View sender) {
        final ITagDevice device = (ITagDevice) sender.getTag();
        if (device == null) {
            ITagApplication.handleError(new Exception("No device"));
            return;
        }

        SetNameDialogFragment.device = device;
        new SetNameDialogFragment().show(getFragmentManager(), "setname");
    }
/*
1. do not want to use appcompat
2. v28 of appcompat is not compatible with com.google.firebase:firebase-core:16.0.4
    @Override
    protected void onRequestPermissionsResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            switch (requestCode) {
                case REQUEST_ENABLE_BT:
                    setupContent();
                    break;
                case REQUEST_ENABLE_LOCATION:
                    if (LeScanner.isScanRequestAbortedBecauseOfPermission && mBluetoothAdapter != null) {
                        LeScanner.startScan(mBluetoothAdapter, this);
                    }
                    break;
            }
        }

    }
*/
    @Override
    public void onStartScan() {
        setupContent();
    }

    @Override
    public void onNewDeviceScanned(LeScanResult result) {
        setupContent();
    }

    @Override
    public void onTick(int tick, int max) {
        setupProgressBar();
    }

    @Override
    public void onStopScan() {
        setupContent();
    }


    @Override
    public void onDbChange() {
        setupContent();
    }

    @Override
    public void onDbAdd(ITagDevice device) {

    }

    @Override
    public void onDbRemove(ITagDevice device) {

    }

    public void onOpenBTSettings(View ignored) {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivity(enableBtIntent);
    }

}
