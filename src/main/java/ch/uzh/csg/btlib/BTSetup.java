package ch.uzh.csg.btlib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

public class BTSetup {
	
	final private static UUID COINBLESK_SERVICE_UUID = UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8");
	final private static AdvertiseData ADVERTISE_DATA = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(COINBLESK_SERVICE_UUID)).build();
	final private static AdvertiseSettings ADVERTISE_SETTINGS = new AdvertiseSettings.Builder()
		.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
		.setConnectable(true)
		.setTimeout(180000)
		.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build();
	final private static AdvertiseCallback ADVERTISE_CALLBACK = new AdvertiseCallback() {
		@Override
		public void onStartFailure(int errorCode) {
			Log.e(TAG, "could not start BTLE advertising");
		}
	};
	final private static String TAG = "ch.uzh.csg.btlib.BTSetup";
	
	final private BluetoothGattServer server;
	final private Handler mHandler;
	final private BluetoothManager bluetoothManager;
	final private BluetoothAdapter bluetoothAdapter;
	
	final public boolean DEBUG = true;
	
	// Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 1000000;
	
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
			public void onCharacteristicWriteRequest(BluetoothDevice device,
					int requestId, BluetoothGattCharacteristic characteristic,
					boolean preparedWrite, boolean responseNeeded, int offset,
					byte[] value) {
				System.err.println("got desc request!");
				if(DEBUG) {
					Log.d(TAG, "got request: "+Arrays.toString(value));
				}
				
				final byte[] reply = new byte[20];
				if(DEBUG) {
					Log.d(TAG, "reply with: "+Arrays.toString(reply));
				}
				server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, reply);
			}
			
			@Override
			public void onConnectionStateChange(BluetoothDevice device,
					int status, int newState) {
				if(DEBUG) {
					Log.d(TAG, "connected: "+newState+ " / " + BluetoothGatt.STATE_CONNECTED);
				}
			}
			
			@Override
			public void onServiceAdded(int status, BluetoothGattService service) {
				if(DEBUG) {
					Log.d(TAG, "service added: "+service);
				}
			}
		});
		
		BluetoothGattService service = new BluetoothGattService(COINBLESK_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
	    
		BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
	    		UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"), BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);
	    service.addCharacteristic(characteristic);
		server.addService(service);
		mHandler = new Handler();
		
		startLeAdvertising(bluetoothAdapter);
	}
	
	private static boolean startLeAdvertising(BluetoothAdapter bluetoothAdapter) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if (advertiser == null) {
			return false;
		}

		advertiser.startAdvertising(ADVERTISE_SETTINGS, ADVERTISE_DATA, ADVERTISE_CALLBACK);
		return true;
	}
	
	private static boolean stopLeAdvertising(BluetoothAdapter bluetoothAdapter) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if (advertiser == null) {
			return false;
		}
		advertiser.stopAdvertising(ADVERTISE_CALLBACK);
		return true;
	}
	
	public void connect(Activity activity) {
		scanLeDevice(activity);
	}
	
	
	public void connect(Activity activity, BluetoothDevice device) {
		
		server.connect(device, false);
		BluetoothGatt bluetoothGatt = device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				if (newState == BluetoothGatt.STATE_CONNECTED) {
			        gatt.discoverServices();
			    }
			}
			
			@Override
			public void onServicesDiscovered(BluetoothGatt gatt, int status) {
				System.err.println("device discovered");
				if (status == BluetoothGatt.GATT_SUCCESS) {
					System.err.println("service: " + gatt.getServices());
					BluetoothGattService ser = gatt.getService(UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8"));
					BluetoothGattCharacteristic car = ser.getCharacteristic(UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"));
					car.setValue(new byte[20]);
					System.err.println("service: " + gatt.getServices());
					boolean write = gatt.writeCharacteristic(car);

					System.err.println("connected!!! "+status + "/");
				}			       
			}
			
			@Override
			public void onCharacteristicWrite(BluetoothGatt gatt,
					BluetoothGattCharacteristic characteristic, int status) {
				System.err.println("here3 "+characteristic.getValue().length);
			}
			
		});
		
	}
	
	public void scanLeDevice(final Activity activity) {
		final BluetoothLeScanner sc = bluetoothAdapter.getBluetoothLeScanner();
		final ScanCallback scb = new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				// if(result.)
				System.err.println("result1: " + callbackType);
				System.err.println("result2: " + result.getDevice());
				connect(activity, result.getDevice());
				sc.stopScan(this);
			}

			@Override
			public void onScanFailed(int errorCode) {
				System.err.println("FAIRDE " + errorCode);
			}
		};

		// Stops scanning after a pre-defined scan period.
		mHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				sc.stopScan(scb);
			}
		}, SCAN_PERIOD);
		// bluetoothAdapter.startLeScan(new
		// UUID[]{UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8")},
		// mLeScanCallback);
		List<ScanFilter> scf = new ArrayList<ScanFilter>();
		// scf.add(new
		// ScanFilter.Builder().setDeviceAddress("B4:CE:F6:B5:9E:B8").build());
		scf.add(new ScanFilter.Builder().setServiceUuid(
				ParcelUuid.fromString("90b26ed7-7200-40ee-9707-5becce10aac8"))
				.build());
		sc.startScan(
				scf,
				new ScanSettings.Builder().setScanMode(
						ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb);

	}
	
    public void shutdownServer() {
		server.close();
    }
	
}
