package ch.uzh.csg.btlib;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcResponder;
import ch.uzh.csg.comm.NfcResponseHandler;

public class BTResponderSetup {
	
	final public static UUID COINBLESK_SERVICE_UUID = UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8");
	
	//private static final UUID COINBLESK_REPLY = UUID.fromString("1ac500ae-ce41-4e2b-a54a-05c8b6a78e35");
	private final UUID localUUID;
	
	final private static AdvertiseData ADVERTISE_DATA = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(COINBLESK_SERVICE_UUID)).build();
	final private static AdvertiseSettings ADVERTISE_SETTINGS = new AdvertiseSettings.Builder()
		.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
		.setConnectable(true)
		.setTimeout(180 * 1000)
		.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build();
	final private static AdvertiseCallback ADVERTISE_CALLBACK = new AdvertiseCallback() {
		@Override
		public void onStartFailure(int errorCode) {
			Log.e(TAG, "could not start BTLE advertising");
		}
	};
	final private static String TAG = "ch.uzh.csg.btlib.BTResponderSetup";
	
	private BluetoothGattServer server;
	private BluetoothAdapter bluetoothAdapter;
	final private AtomicReference<BluetoothGattCharacteristic> remoteUUIDref = new AtomicReference<>();
	
	public BTResponderSetup(UUID localUUID) {
		this.localUUID = localUUID;
	}
	
	
	public void advertise(final NfcResponseHandler responseHandler, final Activity activity) {
		
		// Use this check to determine whether BLE is supported on the device. Then
		// you can selectively disable BLE-related features.
		if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
		    Toast.makeText(activity, "n/a", Toast.LENGTH_SHORT).show();
		    activity.finish();
		}
		
		// Initializes Bluetooth adapter.
		BluetoothManager bluetoothManager =
		        (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
		bluetoothAdapter = bluetoothManager.getAdapter();
		
		// Ensures Bluetooth is available on the device and it is enabled. If not,
		// displays a dialog requesting user permission to enable Bluetooth.
		if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
		    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		    activity.startActivityForResult(enableBtIntent, 1);
		}
		if(!bluetoothAdapter.isMultipleAdvertisementSupported()) {
			Toast.makeText(activity, "peripheral mode n/a", Toast.LENGTH_SHORT).show();
		    activity.finish();
		}
		server = bluetoothManager.openGattServer(activity, new BluetoothGattServerCallback() {
			
			private byte[] response = null;
			private NfcResponder responder = new NfcResponder(responseHandler, 20);
			
			@Override
			public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
					BluetoothGattCharacteristic characteristic) {
				if(Config.DEBUG) {
					Log.d(TAG, "got request read, send back: "+Arrays.toString(response));
				}
				server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response);
			}
			
			@Override
			public void onCharacteristicWriteRequest(final BluetoothDevice device,
					final int requestId, BluetoothGattCharacteristic characteristic,
					boolean preparedWrite, boolean responseNeeded, int offset,
					byte[] value) {
				if(Config.DEBUG) {
					Log.d(TAG, "got request write: "+Arrays.toString(value));
				}
				
				byte[] response = responder.processIncomingData(value);
				if(Config.DEBUG) {
					Log.d(TAG, "send back: "+Arrays.toString(response));
				}
				server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response);
				
				
				if(remoteUUIDref.get() != null) {
					BluetoothGattCharacteristic characteristicReply = remoteUUIDref.get();
					if(Config.DEBUG) {
						Log.d(TAG, "we have a server reply addrress: "+characteristicReply.toString());
					}
					characteristicReply.setValue(response);
					server.notifyCharacteristicChanged(device, characteristicReply, true);
				} else {
					if(Config.DEBUG) {
						Log.d(TAG, "no server reply addrress");
					}
				}
			}
			
			@Override
			public void onMtuChanged(BluetoothDevice device, int mtu) {
				if(Config.DEBUG) {
					Log.d(TAG, "MTU changed to " + mtu);
				}
				responder = new NfcResponder(responseHandler, mtu);
			}
			
			
			@Override
			public void onConnectionStateChange(BluetoothDevice device,
					int status, int newState) {
				if(Config.DEBUG) {
					Log.d(TAG, "connected: "+newState+ " / " + BluetoothGatt.STATE_CONNECTED);
				}
			}
		});
		
		BluetoothGattService service = new BluetoothGattService(COINBLESK_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
	    
		BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
				localUUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
	    service.addCharacteristic(characteristic);
		server.addService(service);
		
		startLeAdvertising(bluetoothAdapter);
		
	}
	
	public void setRemoteUUID(UUID remoteUUID) {
		final BluetoothGattCharacteristic characteristicReply =
		        new BluetoothGattCharacteristic(remoteUUID,
		                BluetoothGattCharacteristic.PROPERTY_INDICATE,
		                BluetoothGattCharacteristic.PERMISSION_WRITE);
		remoteUUIDref.set(characteristicReply);
	}
	
	public void stopAdvertise() {
		stopLeAdvertising(bluetoothAdapter);
	}
	
	private static boolean startLeAdvertising(BluetoothAdapter bluetoothAdapter) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if (advertiser == null) {
			return false;
		}
		if(Config.DEBUG) {
			Log.d(TAG, "start advertising");
		}
		advertiser.startAdvertising(ADVERTISE_SETTINGS, ADVERTISE_DATA, ADVERTISE_CALLBACK);
		return true;
	}
	
	private static boolean stopLeAdvertising(BluetoothAdapter bluetoothAdapter) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if(Config.DEBUG) {
			Log.d(TAG, "stop advertising");
		}
		if (advertiser == null) {
			return false;
		}
		advertiser.stopAdvertising(ADVERTISE_CALLBACK);
		return true;
	}
	
	
	
}
