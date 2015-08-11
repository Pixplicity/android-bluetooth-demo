package com.pixplicity.bluetoothdemo;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

/**
 * Main screen of the application. This is where all the magic happens!
 * Usually, to keep your code clean you'd want to move all the logic concerning bluetooth to a
 * separate controller, a {@link Service service}, a utility class or a combination of those.
 */
public class MainActivity extends AppCompatActivity implements LeScanCallback {

    /**
     * The GATT standard defines this UUID as the identifier of the update notification descriptor,
     * i.e. the descriptor of a characteristic that defines if you will receive updates on the
     * value of the characteristic.
     */
    private static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // TODO Change this value to the UUID of the service you want to communicate with
    private static final UUID SERVICE_UUID = UUID.fromString("e0a9b597-68f7-4f45-91b1-8de008987048");
    // TODO change this value to the UUID of the characteristic you want to write to
    private static final UUID CHARACTERISTIC_A_WRITE = UUID.fromString("186616c7-2993-4ef5-b2a9-04fae246cbb6");
    // TODO change this value to the UUID of the characteristic you want to receive updates from
    private static final UUID CHARACTERISTIC_B_READ = UUID.fromString("f14b30fd-d8ad-4ed4-aac0-6d27465b9601");

    /**
     * Request code used when starting the bluetooth settings Activity
     */
    private static final int REQUEST_ENABLE_BT = RESULT_FIRST_USER;

    private static final String TAG = MainActivity.class.getSimpleName();

    // UI elements
    private Button mBtScan, mBtWrite;
    private EditText mEtUUID;
    private View mScanResults;
    private TextView mTvScanResults, mTvStatus, mTvCharacteristic;
    private ProgressBar mProgress;

    // Bluetooth objects
    private BluetoothAdapter mAdapter;
    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;

    private boolean mIsScanning = false;
    private boolean mSearchIsUUID;
    private byte mWriteValue = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize views
        mBtScan = (Button) findViewById(R.id.bt_scan);
        mBtWrite = (Button) findViewById(R.id.bt_write);
        mEtUUID = (EditText) findViewById(R.id.et_uuid);
        mProgress = (ProgressBar) findViewById(R.id.busy);
        mScanResults = findViewById(R.id.scan_results);
        mTvScanResults = (TextView) findViewById(R.id.tv_scan_results);
        mTvStatus = (TextView) findViewById(R.id.tv_status);
        mTvCharacteristic = (TextView) findViewById(R.id.tv_characteristic);

        // Button listeners
        mBtScan.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(@NonNull View view) {
                startScan();
            }
        });
        findViewById(R.id.bt_clear).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(@NonNull View view) {
                mEtUUID.setText("");
            }
        });
        mBtWrite.setText(getString(R.string.bt_write, mWriteValue));
        mBtWrite.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(@NonNull View v) {
                write();
            }
        });

        // Initialize the adapter
        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = btManager.getAdapter();
        if (!isBluetoothEnabled()) {
            // If bluetooth is not enabled, we ask the user to do so
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // If the permission android.permission.BLUETOOTH_ADMIN is included in the manifest,
            // then we could also enable bluetooth without requiring a user action by doing:
            // mAdapter.enable();
            // ...But that is not nice towards the user.
        }
    }

    /**
     * Updates the status TextView with the given string resource.
     * Can be called from a background thread.
     *
     * @param strId The resource id of the String to display.
     */
    private void showStatus(final int strId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTvStatus.setText(strId);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Enable the scan button if bluetooth is enabled and if
        // it is not already scanning
        mBtScan.setEnabled(isBluetoothEnabled() && !mIsScanning);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mIsScanning && mAdapter != null) {
            // Stop scanning! We're done!
            mAdapter.stopLeScan(this);
            scanStopped();
        }
        if (mGatt != null) {
            // Clean up! This is important, as not closing the GATT connection can
            // cause problems when we want to reconnect later on.
            // Depending on the Android version there's also a very limited number of
            // simultaneous connections available (system wide), so we should free up connections
            // as soon as we can.
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
    }

    /**
     * Checks if bluetooth is enabled in the system settings
     *
     * @return {@code true} if bluetooth is available and enabled, {@code false} otherwise
     */
    public boolean isBluetoothEnabled() {
        return mAdapter != null && mAdapter.isEnabled();
    }

    /**
     * Start scanning for nearby devices. If a UUID is entered in the EditText, it will scan
     * for that specific service. If a MAC address or nothing is entered it will scan for all
     * devices.
     *
     * @see #onLeScan(BluetoothDevice, int, byte[])
     */
    private void startScan() {
        mIsScanning = true;
        mProgress.setVisibility(View.VISIBLE);
        mBtScan.setEnabled(false);
        // If the input contains a colon, let's assume it is a MAC-address
        if (TextUtils.isEmpty(mEtUUID.getText()) || mEtUUID.getText().toString().contains(":")) {
            // Start a regular scan for all devices
            mSearchIsUUID = false;
            mAdapter.startLeScan(this);
        } else {
            String uuidStr = mEtUUID.getText().toString();
            try {
                mSearchIsUUID = true;
                UUID uuid = UUID.fromString(uuidStr);
                // Start a scan for a service with a specific UUID
                mAdapter.startLeScan(new UUID[]{uuid}, this);
            } catch (IllegalArgumentException e) {
                // Abort scanning
                e.printStackTrace();
                Toast.makeText(this, R.string.toast_invalid_uuid, Toast.LENGTH_LONG).show();
                scanStopped();
            }
        }
    }

    /**
     * Closes the GATT connection (if any) and updates the UI to show
     * the 'disconnected' state. Can be called from background threads.
     */
    private void cleanUp() {
        showStatus(R.string.status_disconnected);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                scanStopped();
                mTvCharacteristic.setVisibility(View.GONE);
            }
        });

        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
    }

    /**
     * Enables the UI elements to start a new scan.
     */
    private void scanStopped() {
        mIsScanning = false;
        mProgress.setVisibility(View.INVISIBLE);
        mBtScan.setEnabled(true);
    }

    /**
     * Writes a value (alternating 0 and 1) to the characteristics
     */
    private void write() {
        if (mWriteCharacteristic == null) {
            Log.e(TAG, "There's no characteristic to write to");
        }

        // Disable until write has finished to prevent sending faster than the connection can handle
        mBtWrite.setEnabled(false);

        if ((mWriteCharacteristic.getProperties() | BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
            Log.i(TAG, "Writing data to bluetooth device...");
            mWriteCharacteristic.setValue(new byte[]{mWriteValue});
            mGatt.writeCharacteristic(mWriteCharacteristic);
        } else {
            Log.w(TAG, "Characteristic " + CHARACTERISTIC_A_WRITE + " not writable");
        }
    }

    /**
     * Called for every device that is found during the scan
     *
     * @param device     The device that is found
     * @param rssi       The received signal strength indication
     * @param scanRecord Extra data concerning the scanned device
     */
    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        mScanResults.setVisibility(View.VISIBLE);
        mTvScanResults.append(getString(R.string.found_device, device.getAddress()));
        String search = mEtUUID.getText().toString();

        if (mSearchIsUUID) {
            // When scanning for a service uuid, we can safely assume that the found devices
            // have the service we're
            connectDevice(device);
        } else if (search.contains(":") && device.getAddress().equals(search)) {
            // Does the device address match our search term?
            connectDevice(device);
        }
    }

    /**
     * Starts connecting to a device. The device is obtained a scan.
     *
     * @param device The device to connect with
     */
    private void connectDevice(final BluetoothDevice device) {
        mTvStatus.setVisibility(View.VISIBLE);
        mTvStatus.setText(R.string.status_connecting);

        // We've found the device we want, so stop scanning.
        // This is important, because scanning is the most battery intensive part of the process.
        mAdapter.stopLeScan(this);
        scanStopped();

        // Connect to the device
        mGatt = device.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    switch (newState) {
                        case BluetoothProfile.STATE_CONNECTED:
                            showStatus(R.string.status_discovering);
                            // Start discovering services.
                            // Once done, the onServicesDiscovered callback will be called.
                            if (!gatt.discoverServices()) {
                                // If it fails, clean up
                                cleanUp();
                            }
                            break;
                        case BluetoothProfile.STATE_DISCONNECTED:
                            // The connection was closed, update the interface:
                            showStatus(R.string.status_disconnected);
                            break;
                    }
                } else {
                    // Connection failed, clean up
                    cleanUp();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                // Find the service we want to use
                BluetoothGattService service = gatt.getService(SERVICE_UUID);

                // Find the characteristic that we want to write to
                mWriteCharacteristic = service.getCharacteristic(CHARACTERISTIC_A_WRITE);

                // Find the characteristic that we want to read from
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_B_READ);

                // Enable notifications of that characteristic
                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    Log.w(TAG, "Unable to get notifications for characteristic " + CHARACTERISTIC_B_READ);
                    return;
                }
                // Enable notifications even further by enabling it in the characteristic's descriptors
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(descriptor)) {
                    Log.w(TAG, "Unable to write to descriptor of characteristic " + characteristic.getUuid());
                    cleanUp();
                } else {
                    showStatus(R.string.status_connected);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            findViewById(R.id.read_write).setVisibility(View.VISIBLE);
                        }
                    });
                }
            }

            /**
             * Once notifications are enabled, this method is called every time the value of the characteristic changes.
             *
             * @param gatt The GATT connection
             * @param characteristic The characteristic that has changed.
             */
            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
                Log.i(TAG, "Characteristic changed: " + characteristic.getUuid() + " = " + characteristic.getValue()[0]);
                // Updating the interface needs to be executed on the UI thread
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // The value can be of a variety of types. In this example we check for a
                        // single byte; your actual device might give a String or integer instead
                        byte value = characteristic.getValue()[0];
                        mTvCharacteristic.setVisibility(View.VISIBLE);
                        mTvCharacteristic.setText(String.valueOf(value));
                    }
                });
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Unable to write to characteristic " + characteristic.getUuid());
                    cleanUp();
                } else {
                    // Update interface
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mBtWrite.setEnabled(true);
                            mWriteValue = (byte) ((mWriteValue + 1) % 2);
                            mBtWrite.setText(getString(R.string.bt_write, mWriteValue));
                        }
                    });
                }
            }
        });
    }
}
