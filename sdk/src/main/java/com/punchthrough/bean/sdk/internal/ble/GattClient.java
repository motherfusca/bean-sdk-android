package com.punchthrough.bean.sdk.internal.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.punchthrough.bean.sdk.internal.battery.BatteryProfile;
import com.punchthrough.bean.sdk.internal.device.DeviceProfile;
import com.punchthrough.bean.sdk.internal.serial.GattSerialTransportProfile;
import com.punchthrough.bean.sdk.internal.upload.firmware.OADProfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * Encapsulation of a GATT client in a typical central/peripheral BLE connection where the
 * GATT client is running on the central device.
 */
public class GattClient {

    private static final String TAG = "GattClient";

    // Profiles
    private final GattSerialTransportProfile mSerialProfile;
    private final DeviceProfile mDeviceProfile;
    private final BatteryProfile mBatteryProfile;
    private final OADProfile mOADProfile;
    private List<BaseProfile> mProfiles = new ArrayList<>(10);

    // Internal dependencies
    private BluetoothGatt mGatt;
    private ConnectionListener connectionListener;

    // Internal state
    private Queue<Runnable> mOperationsQueue = new ArrayDeque<>(32);
    private boolean mOperationInProgress = false;
    private boolean mConnected = false;
    private boolean mDiscoveringServices = false;

    public GattClient() {
        mSerialProfile = new GattSerialTransportProfile(this);
        mDeviceProfile = new DeviceProfile(this);
        mBatteryProfile = new BatteryProfile(this);
        mOADProfile = new OADProfile(this);
        mProfiles.add(mSerialProfile);
        mProfiles.add(mDeviceProfile);
        mProfiles.add(mBatteryProfile);
        mProfiles.add(mOADProfile);
    }

    public GattClient(Handler handler) {
        mSerialProfile = new GattSerialTransportProfile(this, handler);
        mDeviceProfile = new DeviceProfile(this);
        mBatteryProfile = new BatteryProfile(this);
        mOADProfile = new OADProfile(this);
        mProfiles.add(mSerialProfile);
        mProfiles.add(mDeviceProfile);
        mProfiles.add(mBatteryProfile);
    }

    private final BluetoothGattCallback mBluetoothGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionListener.onConnectionFailed();
                return;
            }

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mConnected = true;

                // Bean is connected, before alerting the ConnectionListener(s), we must
                // discover available services (lookup GATT table).
                discoverServices();
            }

            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                mOperationsQueue.clear();
                mOperationInProgress = false;
                mConnected = false;
                connectionListener.onDisconnected();
                for (BaseProfile profile : mProfiles) {
                    profile.onBeanDisconnected();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            mDiscoveringServices = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
            } else {

                // Tell each profile that they are ready and to do any other further configuration
                // that may be necessary such as looking up available characteristics.
                for (BaseProfile profile : mProfiles) {
                    profile.onProfileReady();
                    profile.onBeanConnected();
                }

                // Alert ConnectionListener(s) and profiles that the Bean is ready (connected)
                connectionListener.onConnected();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
            } else {
                fireCharacteristicsRead(characteristic);
                executeNextOperation();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
            } else {
                fireCharacteristicWrite(characteristic);
                executeNextOperation();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            fireCharacteristicChanged(characteristic);
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireDescriptorRead(descriptor);
            executeNextOperation();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
                return;
            }
            fireDescriptorWrite(descriptor);
            executeNextOperation();
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect();
            }
        }
    };

    private void fireDescriptorRead(BluetoothGattDescriptor descriptor) {
        for (BaseProfile profile : mProfiles) {
            profile.onDescriptorRead(this, descriptor);
        }
    }

    private synchronized void queueOperation(Runnable operation) {
        mOperationsQueue.offer(operation);
        if (!mOperationInProgress) {
            executeNextOperation();
        }
    }

    private synchronized void executeNextOperation() {
        Runnable operation = mOperationsQueue.poll();
        if (operation != null) {
            mOperationInProgress = true;
            operation.run();
        } else {
            mOperationInProgress = false;
        }
    }

    public void connect(Context context, BluetoothDevice device) {
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
        mConnected = false;
        mGatt = device.connectGatt(context, false, mBluetoothGattCallback);
    }

    private void fireDescriptorWrite(BluetoothGattDescriptor descriptor) {
        for (BaseProfile profile : mProfiles) {
            profile.onDescriptorWrite(this, descriptor);
        }
    }

    private void fireCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        for (BaseProfile profile : mProfiles) {
            profile.onCharacteristicChanged(this, characteristic);
        }
    }

    private void fireCharacteristicWrite(BluetoothGattCharacteristic characteristic) {
        for (BaseProfile profile : mProfiles) {
            profile.onCharacteristicWrite(this, characteristic);
        }
    }

    private void fireCharacteristicsRead(BluetoothGattCharacteristic characteristic) {
        for (BaseProfile profile : mProfiles) {
            profile.onCharacteristicRead(this, characteristic);
        }
    }

    /****************************************************************************
                                  PUBLIC API
     ****************************************************************************/

    /**
     * Sets a listener that will be alerted on connection related events
     *
     * @param listener ConnectionListener object
     */
    public void setListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public boolean isConnected() {
        return mConnected;
    }

    public List<BluetoothGattService> getServices() {
        return mGatt.getServices();
    }

    public BluetoothGattService getService(UUID uuid) {
        return mGatt.getService(uuid);
    }

    public boolean discoverServices() {
        if (mDiscoveringServices) {
            return true;
        }
        mDiscoveringServices = true;
        return mGatt.discoverServices();
    }

    public synchronized boolean readCharacteristic(final BluetoothGattCharacteristic characteristic) {
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    mGatt.readCharacteristic(characteristic);
                }
            }
        });
        return true;
    }

    public synchronized boolean writeCharacteristic(final BluetoothGattCharacteristic characteristic) {
        final byte[] value = characteristic.getValue();
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    characteristic.setValue(value);
                    mGatt.writeCharacteristic(characteristic);
                }
            }
        });
        return true;
    }

    public boolean readDescriptor(final BluetoothGattDescriptor descriptor) {
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    mGatt.readDescriptor(descriptor);
                }
            }
        });
        return true;
    }

    public boolean writeDescriptor(final BluetoothGattDescriptor descriptor) {
        final byte[] value = descriptor.getValue();
        queueOperation(new Runnable() {
            @Override
            public void run() {
                if (mGatt != null) {
                    descriptor.setValue(value);
                    mGatt.writeDescriptor(descriptor);
                }
            }
        });
        return true;
    }

    public boolean readRemoteRssi() {
        return mGatt.readRemoteRssi();
    }

    public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enable) {
        return mGatt.setCharacteristicNotification(characteristic, enable);
    }

    private boolean connect() {
        return mGatt != null && mGatt.connect();
    }

    public void disconnect() {
        mGatt.disconnect();
    }

    public synchronized void close() {
        if (mGatt != null) {
            mGatt.close();
        }
        mGatt = null;
    }

    public GattSerialTransportProfile getSerialProfile() {
        return mSerialProfile;
    }

    public DeviceProfile getDeviceProfile() {
        return mDeviceProfile;
    }

    public BatteryProfile getBatteryProfile() {
        return mBatteryProfile;
    }

    public OADProfile getOADProfile() {
        return mOADProfile;
    }

    // This listener is only for communicating with the Bean class
    public static interface ConnectionListener {
        public void onConnected();

        public void onConnectionFailed();

        public void onDisconnected();

    }
}
