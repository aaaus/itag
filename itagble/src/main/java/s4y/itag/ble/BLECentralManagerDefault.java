package s4y.itag.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import static android.bluetooth.BluetoothProfile.GATT;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;

class BLECentralManagerDefault implements BLECentralManagerInterface, AutoCloseable {
    private static final String L = BLECentralManagerDefault.class.getName();
    private final Context context;
    private final HandlerThread operationsThread = new HandlerThread("BLE Central Manager operations");
    private final Handler operationsHandler;
    private final Map<String, BLEPeripheralInterace> scanned = new HashMap<>();

    private final BLECentralManagerObservables observables = new BLECentralManagerObservables();
    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice bluetoothDevice, int rssi, byte[] data) {
            if (BuildConfig.DEBUG) {
                Log.d(L,"onLeScan address="+bluetoothDevice.getAddress()+" rsss="+ rssi + " thread="+Thread.currentThread().getName());
            }
            BLEPeripheralInterace peripheral = scanned.get(bluetoothDevice.getAddress());
            if (peripheral == null) {
                peripheral = new BLEPeripheralDefault(
                        context,
                        BLECentralManagerDefault.this,
                        bluetoothDevice);
                scanned.put(bluetoothDevice.getAddress(), peripheral);
            }
            observables
                    .observablePeripheralDiscovered
                    .broadcast(new BLEDiscoveryResult(
                            peripheral,
                            rssi,
                            data
                    ));
        }
    };

    BLECentralManagerDefault(Context context) {
        this.context = context;
        operationsThread.start();
        operationsHandler = new Handler(operationsThread.getLooper());
    }

    private BluetoothManager getManager() {
        return (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager bluetoothManager = getManager();
        if (bluetoothManager == null)
            return null;
        return bluetoothManager.getAdapter();
    }

    public boolean canScan() {
        return true;
    }

    @NonNull
    @Override
    public BLEState state() {
        BluetoothAdapter bluetoothAdapter = getAdapter();
        if (bluetoothAdapter == null)
            return BLEState.NO_ADAPTER;
        if (bluetoothAdapter.isEnabled())
            return BLEState.OK;
        else
            return BLEState.NOT_ENABLED;
    }

    @Override
    public BLEError enable() {
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null) {
            return BLEError.noAdapter;
        }
        adapter.enable();
        return BLEError.ok;
    }

    private boolean isScanning = false;

    private boolean isScanning(BluetoothAdapter adapter) {
        return  adapter != null && isScanning;
    }

    public boolean isScanning() {
        BluetoothAdapter adapter = getAdapter();
        return  adapter != null && isScanning;
    }

    public void startScan() {
        scanned.clear();
        BluetoothAdapter adapter = getAdapter();
        if (BuildConfig.DEBUG) {
            Log.d(L,"startLeScan, thread="+Thread.currentThread().getName()+", adapter="+(adapter==null?"null":"not null"));
        }
        if (adapter != null) {
            if (!isScanning(adapter)) {
                adapter.startLeScan(leScanCallback);
                isScanning = true;
            }
        }
    }

    public void stopScan() {
        scanned.clear();
        BluetoothAdapter adapter = getAdapter();
        if (BuildConfig.DEBUG) {
            Log.d(L,"stopLeScan, thread="+Thread.currentThread().getName()+", adapter="+(adapter==null?"null":"not null")+" isCanning="+isScanning(adapter));
        }
        if (isScanning(adapter)) {
            try {
                adapter.stopLeScan(leScanCallback);
            }catch (NullPointerException ignored) {

            }
            isScanning = false;
        }
    }

    public BLEPeripheralInterace retrievePeripheral(@NonNull String id) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(id);
        return new BLEPeripheralDefault(context, this, device);
    }

    @Override
    public boolean connected(BluetoothDevice device) {
        BluetoothManager bluetoothManager = getManager();
        if (bluetoothManager == null)
            return false;
        int state = bluetoothManager.getConnectionState(device, GATT);
        return state == STATE_CONNECTED;
        // List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.STATE_CONNECTED);
    }

    @Override
    public BLECentralManagerObservablesInterface observables() {
        return observables;
    }

    @Override
    public void postOperation(Runnable runnable) {
        operationsHandler.post(runnable);
    }

    @Override
    public void postOperation(Runnable runnable, long delay) {
        operationsHandler.postDelayed(runnable, delay);
    }

    @Override
    public void cancelOperation(Runnable runnable) {
        operationsHandler.removeCallbacks(runnable);
    }

    @Override
    public void close() {
        operationsThread.quit();
    }
}
