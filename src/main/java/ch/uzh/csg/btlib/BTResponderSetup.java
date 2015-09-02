package ch.uzh.csg.btlib;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import android.os.ParcelUuid;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcMessage;
import ch.uzh.csg.comm.NfcResponder;
import ch.uzh.csg.comm.NfcMessage.Type;

public class BTResponderSetup {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BTResponderSetup.class);
	
	final public static UUID COINBLESK_CHARACTERISTIC_UUID_CLASSIC = UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8");
	final public static UUID COINBLESK_CHARACTERISTIC_UUID_FAST_READ = UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac9");
	
	final private AtomicInteger mtu = new AtomicInteger(0);
	
	private final UUID localUUID;
	
	final private static AdvertiseSettings ADVERTISE_SETTINGS = new AdvertiseSettings.Builder()
		.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
		.setConnectable(true)
		.setTimeout(180 * 1000)
		.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build();
	final private static AdvertiseCallback ADVERTISE_CALLBACK = new AdvertiseCallback() {
		@Override
		public void onStartFailure(int errorCode) {
			LOGGER.error("could not start BTLE advertising");
		}
	};
	
	private BluetoothGattServer server;
	
	final private BluetoothAdapter bluetoothAdapter;
	final private BluetoothManager bluetoothManager;
	//final private AtomicReference<BluetoothGattCharacteristic> remoteUUIDref = new AtomicReference<>();
	
	private static BTResponderSetup instance = null;
	
	public static BTResponderSetup init(UUID localUUID, BluetoothManager bluetoothManager, 
    		BluetoothAdapter bluetoothAdapter) {
    	if(instance == null) {
    		//we need peripheral mode, otherwise it makes no sense to start a server
    		if(!bluetoothAdapter.isMultipleAdvertisementSupported()) {
    			return null;
    		}
    		instance = new BTResponderSetup(localUUID, bluetoothManager, bluetoothAdapter);
    	}
    	return instance;
    }
	
	private BTResponderSetup(UUID localUUID, BluetoothManager bluetoothManager, BluetoothAdapter bluetoothAdapter) {
		this.localUUID = localUUID;
		this.bluetoothAdapter = bluetoothAdapter;
		this.bluetoothManager = bluetoothManager;
	}
	
	
	public void advertise(final NfcResponder responder, final Activity activity) {
		server = bluetoothManager.openGattServer(activity, new BluetoothGattServerCallback() {
			
			final private BlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(1);
			final AtomicInteger seq = new AtomicInteger(0);
			
			@Override
			public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
					BluetoothGattCharacteristic characteristic) {
				if(characteristic.getUuid().equals(BTResponderSetup.COINBLESK_CHARACTERISTIC_UUID_FAST_READ)) {
					
					responder.setMtu(mtu.get() - BTInitiatorSetup.BT_OVERHEAD); 
					
					NfcMessage input = new NfcMessage(Type.FRAGMENT);
					input.sequenceNumber(seq.get() + 1);
					NfcMessage output = responder.processIncomingData(input);
					seq.set(output.sequenceNumber());
					if(Config.DEBUG) {
						LOGGER.debug( "got request fast read, send back: {}", output);
					}
					server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, output.bytes());
					
				} else if(characteristic.getUuid().equals(BTResponderSetup.COINBLESK_CHARACTERISTIC_UUID_CLASSIC)) {
					try {
						byte[] response = queue.take();
						if(Config.DEBUG) {
							LOGGER.debug( "got request classic read, send back: {}", response);
						}
						server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response);
					} catch (InterruptedException e) {
						LOGGER.error("interrupted: ", e);
						server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, new byte[0]);
					}	
				} else {
					LOGGER.error("unknown characterestic: ");
					server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, new byte[0]);
				}
			}
			
			@Override
			public void onCharacteristicWriteRequest(final BluetoothDevice device,
					final int requestId, BluetoothGattCharacteristic characteristic,
					boolean preparedWrite, boolean responseNeeded, int offset,
					byte[] value) {
				if(Config.DEBUG) {
					LOGGER.debug( "got request write: {}", Arrays.toString(value));
				}
				
				responder.setMtu(mtu.get() - BTInitiatorSetup.BT_OVERHEAD); 
				
				NfcMessage input = new NfcMessage(value);
				//byte[] response = responder.processIncomingData(value);
				NfcMessage output = responder.processIncomingData(input);
				seq.set(output.sequenceNumber());
				
				if(output.isGetNextFragment()) {
					server.sendResponse(device, requestId, BTInitiatorSetup.GET_NEXT_FRAGMENT, 0, new byte[0]);
					if(Config.DEBUG) {
						LOGGER.debug( "indicate fragment");
					}
				} else if (output.isPollingResponse()) {
					server.sendResponse(device, requestId, BTInitiatorSetup.POLLING_RESPONSE, 0, new byte[0]);
					if(Config.DEBUG) {
						LOGGER.debug( "indicate polling response");
					}
				} else if (output.isPollingRequest()) {
					server.sendResponse(device, requestId, BTInitiatorSetup.POLLING_REQUEST, 0, new byte[0]);
					if(Config.DEBUG) {
						LOGGER.debug( "indicate polling request");
					}
				} else {
					queue.offer(output.bytes());
					server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
					if(Config.DEBUG) {
						LOGGER.debug( "send back: {}", output);
					}
				}
					
			}
			
			@Override
			public void onMtuChanged(BluetoothDevice device, int mtu2) {
				if(Config.DEBUG) {
					LOGGER.debug( "MTU changed to {}", mtu);
				}
				mtu.set(mtu2);
			}
			
			
			@Override
			public void onConnectionStateChange(BluetoothDevice device,
					int status, int newState) {
				if(Config.DEBUG) {
					LOGGER.debug( "connected: {} / {}", newState, BluetoothGatt.STATE_CONNECTED);
				}
				if (newState == BluetoothGatt.STATE_CONNECTED) {
					responder.getResponseHandler().btTagFound();
				} else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
					responder.getResponseHandler().btTagLost();
				}
			}
		});
		
		BluetoothGattService service = new BluetoothGattService(localUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
	    
		BluetoothGattCharacteristic characteristicClassic = new BluetoothGattCharacteristic(
				COINBLESK_CHARACTERISTIC_UUID_CLASSIC, BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ ,
				BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);
		BluetoothGattCharacteristic characteristicFastRead = new BluetoothGattCharacteristic(
				COINBLESK_CHARACTERISTIC_UUID_FAST_READ, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
		service.addCharacteristic(characteristicClassic);
		service.addCharacteristic(characteristicFastRead);
		server.addService(service);
		
		startLeAdvertising(bluetoothAdapter, localUUID);
		
	}
	
	public void stopAdvertise() {
		stopLeAdvertising(bluetoothAdapter);
	}
	
	public UUID getLocalUUID() {
		return localUUID;
	}
	
	private static boolean startLeAdvertising(BluetoothAdapter bluetoothAdapter, UUID localUUID) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if (advertiser == null) {
			return false;
		}
		if(Config.DEBUG) {
			LOGGER.debug( "start advertising");
		}
		AdvertiseData advertiseData = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(localUUID)).build();
		advertiser.startAdvertising(ADVERTISE_SETTINGS, advertiseData, ADVERTISE_CALLBACK);
		return true;
	}
	
	private static boolean stopLeAdvertising(BluetoothAdapter bluetoothAdapter) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if(Config.DEBUG) {
			LOGGER.debug( "stop advertising");
		}
		if (advertiser == null) {
			return false;
		}
		advertiser.stopAdvertising(ADVERTISE_CALLBACK);
		return true;
	}
}
