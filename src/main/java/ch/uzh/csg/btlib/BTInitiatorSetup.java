package ch.uzh.csg.btlib;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
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
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcInitiator;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcTransceiver;

public class BTInitiatorSetup {
	
	
	final private static String TAG = "ch.uzh.csg.btlib.BTInitiatorSetup";
	
	final private Handler mHandler;
	final private BluetoothManager bluetoothManager;
	final private BluetoothAdapter bluetoothAdapter;
	
	
	
	private final NfcInitiator initiator;
	private final NfcInitiatorHandler initiatorHandler;
	
	private final UUID localUUID;
	
	
	
	// Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 1000000;
	
	public BTInitiatorSetup(final NfcInitiatorHandler initiatorHandler, final Activity activity, UUID localUUID) {
		this.initiatorHandler = initiatorHandler;
		this.initiator = new NfcInitiator(initiatorHandler);
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
		mHandler = new Handler();
		this.localUUID = localUUID;
	}
	
	private void btleDiscovered(NfcTransceiver nfcTransceiver) throws Exception {
		initiator.tagDiscoverHandler().tagDiscovered(nfcTransceiver, false, false, false);
		
	}
	
	public void scanLeDevice(final Activity activity, final UUID remoteUUID) {
		if(Config.DEBUG) {
			Log.d(TAG, "start scanning");
		}
		final BluetoothLeScanner sc = bluetoothAdapter.getBluetoothLeScanner();
		final ScanCallback scb = new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				// if(result.)
				if(Config.DEBUG) {
					Log.d(TAG, "scan result1: " + callbackType);
					Log.d(TAG, "scan result2: " + result.getDevice());
				}
				sc.stopScan(this);
				connect(activity, result.getDevice(), remoteUUID);
			}

			@Override
			public void onScanFailed(int errorCode) {
				Log.e(TAG, "scan failed: " + errorCode);
			}
		};

		// Stops scanning after a pre-defined scan period.
		mHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				sc.stopScan(scb);
			}
		}, SCAN_PERIOD);
		
		List<ScanFilter> scf = new ArrayList<ScanFilter>();
		scf.add(new ScanFilter.Builder().setServiceUuid(
				ParcelUuid.fromString(BTResponderSetup.COINBLESK_SERVICE_UUID.toString()))
				.build());
		sc.startScan(
				scf, new ScanSettings.Builder().setScanMode(
						ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb);

	}
	
	
	public void connect(Activity activity, BluetoothDevice device, final UUID remoteUUID) {
		final AtomicInteger mtu = new AtomicInteger(512);
		device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			BlockingQueue<byte[]> msg = new SynchronousQueue<>();
			
			@Override
			public void onMtuChanged(BluetoothGatt gatt, int mtu2, int status) {
				if(status != BluetoothGatt.GATT_SUCCESS) {
					if(Config.DEBUG) {
						Log.d(TAG, "mtu was *not* set to: "+mtu2+" go for: "+(mtu.get() / 2));
					}
					mtu.set(mtu.get() / 2);
					gatt.requestMtu(mtu.get());
				} else {
					//continue
					initiator.setmaxTransceiveLength(mtu2);
					gatt.discoverServices();
					if(Config.DEBUG) {
						Log.d(TAG, "mtu was set to: "+mtu2);
					}
				}
				
			}
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				if (newState == BluetoothGatt.STATE_CONNECTED) {
					gatt.requestMtu(mtu.get());
			    }
			}
			
			@Override
			public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
				if(Config.DEBUG) {
					Log.d(TAG, "device discovered");
				}
				if (status == BluetoothGatt.GATT_SUCCESS) {
					if(Config.DEBUG) {
						Log.d(TAG, "service: " + gatt.getServices());
					}
					gatt.setCharacteristicNotification(BTInitiatorSetup.this.getIndicateAddress(), true);
					final BluetoothGattService ser = gatt.getService(BTResponderSetup.COINBLESK_SERVICE_UUID);
					final BluetoothGattCharacteristic car = ser.getCharacteristic(remoteUUID);
					
					//car.setValue(NfcMessage.BTLE_INIT);
					new Thread(new Runnable() {
						
						@Override
						public void run() {
							try {
							btleDiscovered(new NfcTransceiver() {
								
								@Override
								public byte[] write(byte[] input) throws Exception {
									car.setValue(input);
									gatt.writeCharacteristic(car);
									return msg.poll(10, TimeUnit.SECONDS);
								}
								
								@Override
								public int maxLen() {
									//not used, as we don't do a handshake
									return mtu.get();
								}
								
								@Override
								public void close() {
									gatt.close();
								}
							});
							} catch (Exception e) {
								initiatorHandler.handleFailed(e.toString());
							}
							
						}
					}).start();
				}			       
			}
			
			/*@Override
			public void onCharacteristicWrite(BluetoothGatt gatt,
					BluetoothGattCharacteristic characteristic, int status) {
				System.err.println("here3 "+characteristic.getValue().length);
				final BluetoothGattService ser = gatt.getService(BTResponderSetup.COINBLESK_SERVICE_UUID);
				final BluetoothGattCharacteristic car = ser.getCharacteristic(remoteUUID);				
				
			}*/
			/*@Override
			public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
					int status) {
				System.err.println("here4 "+characteristic.getValue().length);
				msg.offer(characteristic.getValue());
			}*/
			
			@Override
			public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
				System.err.println("here5 "+characteristic.getValue().length);
				msg.offer(characteristic.getValue());
			}	
		});
	}
	
	public BluetoothGattCharacteristic getIndicateAddress() {
		final BluetoothGattCharacteristic characteristicIndicate =
		        new BluetoothGattCharacteristic(localUUID,
		                BluetoothGattCharacteristic.PROPERTY_INDICATE,
		                BluetoothGattCharacteristic.PERMISSION_WRITE);
		return characteristicIndicate;
	}
}
