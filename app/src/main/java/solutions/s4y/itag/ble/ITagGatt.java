package solutions.s4y.itag.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import solutions.s4y.itag.BuildConfig;
import solutions.s4y.itag.ITagApplication;

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;

public class ITagGatt {
    private static final int DOUBLE_TAG_CLICK_DELAY = 300;

    private static final String LT = ITagGatt.class.getName();

    private static final int NO_ALERT = 0x00;
    //public static final int MEDIUM_ALERT = 0x01;
    private static final int HIGH_ALERT = 0x02;

    private static final UUID IMMEDIATE_ALERT_SERVICE =
            UUID.fromString("00001802-0000-1000-8000-00805f9b34fb");
    private static final UUID FIND_ME_SERVICE =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID LINK_LOSS_SERVICE =
            UUID.fromString("00001803-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID GENERIC_SERVICE =
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID ALERT_LEVEL_CHARACTERISTIC =
            UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");
    /*
    private static final UUID FIND_ME_CHARACTERISTIC =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
            */

    public final String mAddr;
    private BluetoothDevice mDevice;
    private BluetoothGatt mGatt;
    private boolean mIsError;
    private boolean mIsConnected;
    private boolean mIsConnecting;
    private boolean mIsTransmitting;
    private boolean mIsFindingITag;
    private boolean mIsStartingITagFind;
    private boolean mIsStoppingITagFind;
    public int mRssi;
    private int mDevicesCount;
    // for sake of reconnect on status = 133
    private Context mContext;

    private final Handler mHandler = new Handler();

    private BluetoothGattService mServiceImmediateAlert;

    public interface ITagChangeListener {
        void onITagChange(@NonNull final ITagGatt gatt);

        void onITagRssi(@NonNull final ITagGatt gatt, int rssi);

        void onITagClicked(@NonNull final ITagGatt gatt);

        void onITagDoubleClicked(@NonNull final ITagGatt gatt);

        void onITagFindingPhone(@NonNull final ITagGatt gatt, boolean on);

    }

    private static final List<ITagChangeListener> mITagChangeListeners =
            new ArrayList<>(4);

    public static void addOnITagChangeListener(ITagChangeListener listener) {
        if (BuildConfig.DEBUG) {
            if (mITagChangeListeners.contains(listener)) {
                ITagApplication.handleError(new Error("Add duplicate ITagChangeListener listener"));
            }
        }
        mITagChangeListeners.add(listener);
    }

    public static void removeOnITagChangeListener(ITagChangeListener listener) {
        if (BuildConfig.DEBUG) {
            if (!mITagChangeListeners.contains(listener)) {
                ITagApplication.handleError(new Error("Remove nonexisting ITagChangeListener listener"));
            }
        }
        mITagChangeListeners.remove(listener);
    }

    private void notifyITagChanged() {
        for (ITagChangeListener listener : mITagChangeListeners) {
            listener.onITagChange(this);
        }
    }

    private void notifyITagClicked() {
        for (ITagChangeListener listener : mITagChangeListeners) {
            listener.onITagClicked(this);
        }
    }

    private void notifyITagDoubleClicked() {
        for (ITagChangeListener listener : mITagChangeListeners) {
            listener.onITagDoubleClicked(this);
        }
    }

    private void notifyITagRssi(int rssi) {
        for (ITagChangeListener listener : mITagChangeListeners) {
            listener.onITagRssi(this, rssi);
        }
    }

    private void notifyITagFindingPhone(boolean on) {
        for (ITagChangeListener listener : mITagChangeListeners) {
            listener.onITagFindingPhone(this, on);
        }
    }

    private void setCharacteristicNotification(BluetoothGatt bluetoothgatt, BluetoothGattCharacteristic bluetoothgattcharacteristic) {
        bluetoothgatt.setCharacteristicNotification(bluetoothgattcharacteristic, true);
        BluetoothGattDescriptor descriptor = bluetoothgattcharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothgatt.writeDescriptor(descriptor);
        }
    }

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (BuildConfig.DEBUG) {
                Log.d(LT,
                        "GattCallback.onConnectionStateChange: addr=" + gatt.getDevice().getAddress() +
                                " status=" + status +
                                " state=" + newState);
            }

            // the callback is for no connection state changes only
            mHandler.removeCallbacks(mForceDisconnect);

            if (status == GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (BuildConfig.DEBUG) {
                        Log.d(LT,
                                "GattCallback.onConnectionStateChange, STATE_CONNECTED");
                        Log.d(LT,
                                "will gatt.discoverServices");
                    }
                    gatt.discoverServices();
                    // fre resource
                    mContext = null;
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (BuildConfig.DEBUG) {
                        Log.d(LT,
                                "GattCallback.onConnectionStateChange, STATE_DISCONNECTED");
                    }
                    mGatt.close();
                    endConnection();
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(LT, "GattCallback.onConnectionStateChange: not GATT_SUCCESS, status=" + status);
                }
                // 8 to be know as disconnection status
                if (status != 8) {
                    if (status == 133 && mContext != null) {
                        mHandler.postDelayed(() -> connect(mContext, true), 100);
                        // free resource AND avoid endless iteration. Hacky a bit
                        mContext = null;
                    } else {
                        ITagApplication.handleError(new Exception("onConnectionStateChange failed: code=" + status + " state=" + newState));
                        mIsError = true;
                    }
                }else{
                    mIsError = false;
                    mIsConnected = false;
                }
                notifyITagChanged();
                ITagApplication.faITagLost(mIsError);
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (BuildConfig.DEBUG) {
                Log.d(LT, "GattCallback.onServicesDiscovered: addr=" + gatt.getDevice().getAddress());
            }


            if (GATT_SUCCESS == status) {
                if (BuildConfig.DEBUG) {
                    Log.d(LT, "GattCallback.onServicesDiscovered: GATT_SUCCESS");
                }
                for (BluetoothGattService service : gatt.getServices()) {
                    if (BuildConfig.DEBUG) {
                        Log.d(LT, "GattCallback.onServicesDiscovered, iterate service=" + service.getUuid());
                    }
                    if (IMMEDIATE_ALERT_SERVICE.equals(service.getUuid())) {
                        if (BuildConfig.DEBUG) {
                            Log.d(LT, "GattCallback.onServicesDiscovered, iterated service is IMMEDIATE_ALERT_SERVICE");
                        }
                        mServiceImmediateAlert = service;
                        gatt.readCharacteristic(service.getCharacteristic(ALERT_LEVEL_CHARACTERISTIC));
                        setCharacteristicNotification(gatt, mServiceImmediateAlert.getCharacteristics().get(0));
                    } else if (BATTERY_SERVICE.equals(service.getUuid())) {
                        if (BuildConfig.DEBUG) {
                            Log.d(LT, "GattCallback.onServicesDiscovered, iterated service is BATTERY_SERVICE");
                        }
                    } else if (FIND_ME_SERVICE.equals(service.getUuid())) {
                        if (BuildConfig.DEBUG) {
                            Log.d(LT, "GattCallback.onServicesDiscovered, iterated service is FIND_ME_SERVICE");
                        }
                        BluetoothGattCharacteristic characteristicFindMe = service.getCharacteristics().get(0);
                        setCharacteristicNotification(gatt, characteristicFindMe);
                    } else if (LINK_LOSS_SERVICE.equals(service.getUuid())) {
                        if (BuildConfig.DEBUG) {
                            Log.d(LT, "GattCallback.onServicesDiscovered, iterated service is LINK_LOSS_SERVICE");
                        }
                    } else if (GENERIC_SERVICE.equals(service.getUuid())) {
                        if (BuildConfig.DEBUG) {
                            Log.d(LT, "GattCallback.onServicesDiscovered, iterated service is GENERIC_SERVICE");
                        }
                    } else {
                        if (BuildConfig.DEBUG) {
                            ITagApplication.handleError(new Exception("Unknown service: " + service.getUuid().toString()));
                        }
                    }
                }
                mIsError = false; // we need to reset error because of auto connect
                mIsConnecting = false;
                mIsConnected = true;
                if (mIsRssi) {
                    startListenRssi();
                }
                notifyITagChanged();
            } else {
                mIsError = true;

                if (BuildConfig.DEBUG) {
                    Log.d(LT, "GattCallback.onServicesDiscovered: not GATT_SUCCESS");
                }
                ITagApplication.handleError(new Exception("onServicesDiscovered failed"));
                notifyITagChanged();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (BuildConfig.DEBUG) {
                Log.d(LT, "GattCallback.onCharacteristicWrite: addr=" + gatt.getDevice().getAddress()
                        + " characteristic=" + characteristic.getUuid() + " value=" + characteristic.getStringValue(0));
            }
            mIsTransmitting = false;
            if (mIsStartingITagFind) {
                mIsStartingITagFind = false;
                mIsFindingITag = true;
            } else if (mIsStoppingITagFind) {
                mIsStoppingITagFind = false;
                mIsFindingITag = false;
            }
            notifyITagChanged();
            ITagApplication.faITagFound();
        }

        private int mClicksCount = 0;

        private Runnable mWaitForDoubleClick = () -> {
            mClicksCount = 0;
            notifyITagClicked();
        };

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (BuildConfig.DEBUG) {
                Log.d(LT, "GattCallback.onCharacteristicChanged: addr=" + gatt.getDevice().getAddress()
                        + " characteristic=" + characteristic.getUuid() + " value=" + characteristic.getStringValue(0));
            }

            if (mClicksCount == 0) {
                mClicksCount++;
                mHandler.postDelayed(mWaitForDoubleClick, DOUBLE_TAG_CLICK_DELAY);
            } else if (mClicksCount == 1) {
                mHandler.removeCallbacks(mWaitForDoubleClick);
                mClicksCount = 0;
                notifyITagDoubleClicked();
                ITagApplication.faFindPhone();
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            Log.d(LT, "onReadRemoteRssi, addr=" + mAddr + " rssi=" + rssi);
            mRssi = rssi;
            notifyITagRssi(rssi);
        }
    };

    private void endConnection() {
        reset();
        notifyITagChanged();
    }

    private void reset() {
        if (mGatt != null) {
            mGatt.close();
        }
        mGatt = null;
        mIsConnected = false;
        mIsConnecting = false;
        mDevice = null;
        mIsError = false;
        mIsTransmitting = false;
        mIsFindingPhone = false;
        mIsFindingITag = false;
        mIsStartingITagFind = false;
        mIsStoppingITagFind = false;
        mRssi = -1000;
    }

    private void writeCharacteristicAlertLevel(
            @NonNull final BluetoothGattService service,
            int value
    ) {
        if (service.getCharacteristics() == null || service.getCharacteristics().size() == 0) {
            ITagApplication
                    .handleError(
                            new Exception(
                                    "DeviceGatt.writeCharacteristicAlertLevel, no characteristic="
                                            + ITagGatt.ALERT_LEVEL_CHARACTERISTIC));
            return;
        }
        final BluetoothGattCharacteristic characteristic = service.getCharacteristics().get(0);
        characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
        if (BuildConfig.DEBUG) {
            Log.d(LT,
                    "writeCharacteristicAlertLevel: service=" + service.getUuid() +
                            " characteristic=" + characteristic.getUuid() +
                            " value desired=" + value +
                            " value =" + (characteristic.getValue() != null && characteristic.getValue().length > 0 ? characteristic.getValue()[0] : "N/A")
            );
        }
        mGatt.writeCharacteristic(characteristic);
        mIsTransmitting = true;
        notifyITagChanged();
    }

    public void connect(@NonNull final Context contex) {
        connect(contex, false);
    }

    private void connect(@NonNull final Context contex, boolean workaraund133) {
        if (BuildConfig.DEBUG) {
            if (mGatt != null) {
                ITagApplication.handleError(new Exception("DeviceGatt.connect: mGatt!=null"));
            }
            if (mIsConnected) {
                ITagApplication.handleError(new Exception("DeviceGatt.connect: mIsConnected"));
            }
            if (mIsConnecting) {
                ITagApplication.handleError(new Exception("DeviceGatt.connect: mIsConnecting"));
            }
        }
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
        reset();
        mIsConnecting = true;
        if (!workaraund133) { // avoid endless iteration
            mContext = contex;
        }
        notifyITagChanged();
        mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mAddr);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && workaraund133) {
            ITagApplication.handleError(new Exception("The device seems to have a problem. Anti lost feature may fail."));
            mGatt = mDevice.connectGatt(contex, false, mCallback, TRANSPORT_LE);
        } else {
            mGatt = mDevice.connectGatt(contex, true, mCallback);
        }
        mDevicesCount = ITagsDb.getDevices(contex).size();
    }

    private static final int RSSI_INTERVAL_MS = 1000;
    private Runnable mRssiRunable = new Runnable() {
        @Override
        public void run() {
            if (mGatt != null) { // https://github.com/s4ysolutions/itag/issues/14
                mGatt.readRemoteRssi();
            }
            mHandler.postDelayed(this, RSSI_INTERVAL_MS * (mDevicesCount == 0 ? 1 : mDevicesCount));
        }
    };

    private boolean mIsRssi;

    public void startListenRssi() {
        stopListenRssi();
        mIsRssi = true;
        if (isConnected()) {
            if (BuildConfig.DEBUG) {
                Log.d(LT, "startListenRssi, addr=" + mAddr);
            }
            mHandler.postDelayed(mRssiRunable, RSSI_INTERVAL_MS);
        }
    }

    public void stopListenRssi() {
        mIsRssi = false;
        if (BuildConfig.DEBUG) {
            Log.d(LT, "stopListenRssi, addr=" + mAddr);
        }
        mHandler.removeCallbacks(mRssiRunable);
    }

    void close() {
        if (BuildConfig.DEBUG) {
            if (mGatt == null) {
                ITagApplication.handleError(new Exception("DeviceGatt.disconnect: mGatt==null"));
            }
            if (!mIsConnected) {
                ITagApplication.handleError(new Exception("DeviceGatt.connect: !mIsConnected"));
            }
        }
        reset();
        mGatt.close();
    }

    private Runnable mForceDisconnect = this::endConnection;

    public void disconnect() {
        if (BuildConfig.DEBUG) {
            if (mGatt == null) {
                ITagApplication.handleError(new Exception("DeviceGatt.disconnect: mGatt==null"));
            }
            if (!mIsConnected && !mIsConnecting) {
                ITagApplication.handleError(new Exception("DeviceGatt.connect: !mIsConnected && !mIsConnecting"));
            }
        }
        stopListenRssi();
        if (mGatt != null) {// https://github.com/s4ysolutions/itag/issues/12
            mGatt.disconnect();
            mHandler.removeCallbacks(mForceDisconnect);
            mHandler.postDelayed(mForceDisconnect, 3000);
        } else {
            endConnection();
        }
    }

    public void findITag() {
        if (BuildConfig.DEBUG) {
            Log.d(LT, "find iTag for 15sec");
        }

        // TODO: handle not connected
        if (!mIsConnected) {
            ITagApplication.handleError(new Exception("Not connected"));
            return;
        }

        if (mServiceImmediateAlert == null) {
            // TODO: replace with string resource
            ITagApplication.handleError(new Exception("The connected device does not seem to be iTag, sorry"));
            ITagApplication.faNotITag();
            return;
        }

        mIsStartingITagFind = true;
        mIsFindingITag = false;
        mIsStoppingITagFind = false;
        writeCharacteristicAlertLevel(mServiceImmediateAlert, HIGH_ALERT);
        ITagApplication.faFindITag();
    }

    public void stopFindITag() {
        if (BuildConfig.DEBUG) {
            Log.d(LT, "stop find iTag");
        }

        if (!mIsConnected) {
            ITagApplication.handleError(new Exception("Not connected"));
            return;
        }

        if (mServiceImmediateAlert == null) {
            // TODO: replace with string resource
            ITagApplication.handleError(new Exception("The connected device does not seem to be iTag, sorry"));
            return;
        }

        mIsStoppingITagFind = true;
        writeCharacteristicAlertLevel(mServiceImmediateAlert, NO_ALERT);
    }

    public boolean isConnecting() {
        return mIsConnecting;
    }

    public boolean isError() {
        // without mIsConnected mIsError may overwrite other statuse
        // but error may happen beyond connected state boundaries
        return /* mIsConnected && */ mIsError;
    }

    public boolean isConnected() {
        return mIsConnected && !mIsError;
    }

    public boolean isTransmitting() {
        return mIsTransmitting;
    }

    public boolean isFindingITag() {
        return mIsFindingITag || mIsStartingITagFind || mIsStoppingITagFind;
    }

    ITagGatt(String addr) {
        mAddr = addr;
    }

    private boolean mIsFindingPhone;

    void startFindPhone() {
        mIsFindingPhone = true;
        notifyITagFindingPhone(true);
    }

    void stopFindPhone() {
        mIsFindingPhone = false;
        notifyITagFindingPhone(false);
    }

    public boolean isFindingPhone() {
        return mIsFindingPhone;
    }


}