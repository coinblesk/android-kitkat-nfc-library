package ch.uzh.csg.btlib;

import java.util.UUID;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.widget.Toast;

public class BTSetup {
	
	final private BluetoothGattServer server;
	final private Handler mHandler;
	final private BluetoothManager bluetoothManager;
	final private BluetoothAdapter bluetoothAdapter;
	
	// Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;
	
	public BTSetup(Activity activity) {
		// Use this check to determine whether BLE is supported on the device. Then
		// you can selectively disable BLE-related features.
		if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
		    Toast.makeText(activity, "n/a", Toast.LENGTH_SHORT).show();
		    activity.finish();
		}
		
		// Initializes Bluetooth adapter.
		bluetoothManager =
		        (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
		bluetoothAdapter = bluetoothManager.getAdapter();
		
		// Ensures Bluetooth is available on the device and it is enabled. If not,
		// displays a dialog requesting user permission to enable Bluetooth.
		if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
		    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		    activity.startActivityForResult(enableBtIntent, 1);
		}
		server = bluetoothManager.openGattServer(activity, new BluetoothGattServerCallback() {
			@Override
			public void onDescriptorWriteRequest(BluetoothDevice device,
					int requestId, BluetoothGattDescriptor descriptor,
					boolean preparedWrite, boolean responseNeeded, int offset,
					byte[] value) {
				System.err.println("got write request!");
				server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[20]);
			}
			@Override
			public void onCharacteristicWriteRequest(BluetoothDevice device,
					int requestId, BluetoothGattCharacteristic characteristic,
					boolean preparedWrite, boolean responseNeeded, int offset,
					byte[] value) {
				System.err.println("got desc request!");
				super.onCharacteristicWriteRequest(device, requestId, characteristic,
						preparedWrite, responseNeeded, offset, value);
			}
		});
		
		BluetoothGattService service = new BluetoothGattService(UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8"), BluetoothGattService.SERVICE_TYPE_PRIMARY);
	    BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
	    		UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"), BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
	    service.addCharacteristic(characteristic);
		server.addService(service);
		mHandler = new Handler();
		System.err.println("my address: "+bluetoothAdapter.getAddress());
		
	}
	
	public void connect(Activity activity, String address) {		
		final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
		BluetoothGatt bluetoothGatt = device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			@Override
			public void onServicesDiscovered(BluetoothGatt gatt, int status) {
				System.err.println("device discovered");
				super.onServicesDiscovered(gatt, status);
			}
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				System.err.println("connected!!!");
				BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
						UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"), BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
				characteristic.setValue(new byte[20]);
				gatt.writeCharacteristic(characteristic);
			}
			@Override
			public void onDescriptorWrite(BluetoothGatt gatt,
					BluetoothGattDescriptor descriptor, int status) {
				System.err.println("got it "+descriptor.getValue());
			}
		});
		
	}
	
	public void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                	bluetoothAdapter.stopLeScan(mLeScanCallback);
                }
            }, SCAN_PERIOD);
            bluetoothAdapter.startLeScan(new UUID[]{UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8")}, mLeScanCallback);
        } else {
        	bluetoothAdapter.stopLeScan(mLeScanCallback);
        }
    }
	
	public BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
        	server.connect(device, true);
        }
    };
	
    public void shutdownServer() {
		server.close();
    }
	
}
