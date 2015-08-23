package ch.uzh.csg.btlib;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcInitiator;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcMessage;
import ch.uzh.csg.comm.NfcMessage.Type;
import ch.uzh.csg.comm.NfcMessageSplitter;
import ch.uzh.csg.comm.NfcResponder;
import ch.uzh.csg.comm.NfcResponseHandler;
import ch.uzh.csg.comm.NfcTransceiver;
import ch.uzh.csg.nfclib.NfcInitiatorSetup;

public class BTInitiatorSetup {
	
	final private static UUID COINBLESK_SERVICE_UUID = UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8");
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
	final private static String TAG = "ch.uzh.csg.btlib.BTSetup";
	
	final private BluetoothGattServer server;
	final private Handler mHandler;
	final private BluetoothManager bluetoothManager;
	final private BluetoothAdapter bluetoothAdapter;
	
	
	
	private final NfcInitiatorHandler initiatorHandler;
	
	//state
	private final NfcMessageSplitter messageSplitter = new NfcMessageSplitter();
	private final Deque<NfcMessage> messageQueue = new ConcurrentLinkedDeque<NfcMessage>();
	private NfcMessage lastMessageSent;
	
	// Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 1000000;
	
	public BTInitiatorSetup(final NfcInitiatorHandler initiatorHandler,  Activity activity) {
		messageSplitter.maxTransceiveLength(20);
		this.initiatorHandler = initiatorHandler;
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
			
			byte[] response = null;
			
			@Override
			public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
					BluetoothGattCharacteristic characteristic) {
				if(Config.DEBUG) {
					Log.d(TAG, "got request read");
				}
				System.err.println("send back1: "+Arrays.toString(response));
				server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response);
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
	    		UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"), BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);
	    service.addCharacteristic(characteristic);
		server.addService(service);
		mHandler = new Handler();
		advertise();
		
	}
	
	private void btleDiscovered(NfcTransceiver nfcTransceiver) throws Exception {
		initiatorHandler.handleStatus("handshake complete");
		// check if we should resume
		if (!messageQueue.isEmpty()) {
			if (!processMessage(nfcTransceiver)) {
				System.err.println("return false1");
				return;
			}
		}
		// get the complete message
		while (initiatorHandler.hasMoreMessages()) {
			byte[] message = initiatorHandler.nextMessage();
			if (message == null) {
				initiatorHandler.handleFailed("noting to do");
				return;
			}

			// split it
			for (NfcMessage msg : messageSplitter.getFragments(message)) {
				messageQueue.offer(msg);
			}

			if (!processMessage(nfcTransceiver)) {
				System.err.println("return false2");
				return;
			}

		}
		System.err.println("we are out");
	}
	
	private boolean processMessage(NfcTransceiver transceiver) {
		try {
			messageLoop(transceiver);
		} catch (Throwable t) {
			t.printStackTrace();
			initiatorHandler.handleFailed(t.toString());
			return false;
		}
		messageSplitter.clear();
		return true;
	}
	
	private void messageLoop(final NfcTransceiver transceiver) throws Exception {
		if (Config.DEBUG) {
			Log.d(TAG, "start message loop");
		}
		while (!messageQueue.isEmpty()) {
			final NfcMessage request = messageQueue.peek();
			request.sequenceNumber(lastMessageSent);
			
			if (Config.DEBUG) {
				Log.d(TAG, "loop write: "+request+ " / "+Arrays.toString(request.bytes()));
			}
			byte[] response = transceiver.write(request.bytes());
			
			if (response == null) {
				throw new IOException(NfcInitiatorSetup.UNEXPECTED_ERROR);
			}
			NfcMessage responseMessage = new NfcMessage(response);
			if (Config.DEBUG) {
				Log.d(TAG, "loop response: "+responseMessage);
			}
				
			//message successfully sent, remove from queue
			messageQueue.poll();
			initiatorHandler.handleStatus("message fragment sent, queue: "+messageQueue.size());
				
			
			if (!NfcInitiator.validateSequence(request, responseMessage)) {
				if (Config.DEBUG) {
					Log.e(TAG, "sequence error " + request + " / " + response);
				}
				throw new IOException(NfcInitiatorSetup.INVALID_SEQUENCE);
			}
				
			lastMessageSent = request;
				
			switch (responseMessage.type()) {
			case SINGLE:
			case FRAGMENT:
			case FRAGMENT_LAST:
				if(responseMessage.payload().length > 0) {
					//we receive fragments
					switch (responseMessage.type()) {
					case SINGLE:
						initiatorHandler.handleMessageReceived(responseMessage.payload());
						break;
					case FRAGMENT:
						messageSplitter.reassemble(responseMessage);
						messageQueue.offer(new NfcMessage(Type.FRAGMENT));
						break;
					case FRAGMENT_LAST:
						messageSplitter.reassemble(responseMessage);
						initiatorHandler.handleMessageReceived(messageSplitter.data());
						break;
					default:
						throw new RuntimeException("This can never happen");
					}
				} else {
					//we send fragments
					if(messageQueue.isEmpty()) {
						throw new IOException("message queue empty, cannot send fragments");
					}
				}
				break;
			case ERROR:
				throw new IOException("the message "+request+" caused an exception on the other side");
			default:
				throw new IOException("did not expect the type "+responseMessage.type()+" as reply");
			}
		}
		
	}
	
	public void advertise() {
		startLeAdvertising(bluetoothAdapter);
	}

	
	private static boolean startLeAdvertising(BluetoothAdapter bluetoothAdapter) {
		BluetoothLeAdvertiser advertiser = bluetoothAdapter
				.getBluetoothLeAdvertiser();
		if (advertiser == null) {
			return false;
		}
		System.err.println("start adv");
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

		final AtomicInteger mtu = new AtomicInteger(1000);
		BluetoothGatt bluetoothGatt = device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			
			
			BlockingQueue<byte[]> msg = new SynchronousQueue<>();
			
			@Override
			public void onMtuChanged(BluetoothGatt gatt, int mtu2, int status) {
				if(status != BluetoothGatt.GATT_SUCCESS) {
					mtu.set(mtu.get() / 2);
					gatt.requestMtu(mtu.get());
				} else {
					//continue
				}
				
			}
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				if (newState == BluetoothGatt.STATE_CONNECTED) {
			        gatt.discoverServices();
			    }
			}
			
			@Override
			public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
				System.err.println("device discovered");
				if (status == BluetoothGatt.GATT_SUCCESS) {
					System.err.println("service: " + gatt.getServices());
					final BluetoothGattService ser = gatt.getService(UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8"));
					final BluetoothGattCharacteristic car = ser.getCharacteristic(UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"));
					
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
									return 20;
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
					
					
					System.err.println("service: " + gatt.getServices());
					//boolean write = gatt.writeCharacteristic(car);

					//System.err.println("connected!!! "+status + "/" + write);
				}			       
			}
			
			@Override
			public void onCharacteristicWrite(BluetoothGatt gatt,
					BluetoothGattCharacteristic characteristic, int status) {
				System.err.println("here3 "+characteristic.getValue().length);
				final BluetoothGattService ser = gatt.getService(UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8"));
				final BluetoothGattCharacteristic car = ser.getCharacteristic(UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"));
				gatt.readCharacteristic(car);
				//byte[] next = responder.processIncomingData(characteristic.getValue());
				//BluetoothGattService ser = gatt.getService(UUID.fromString("90b26ed7-7200-40ee-9707-5becce10aac8"));
				//BluetoothGattCharacteristic car = ser.getCharacteristic(UUID.fromString("42cf539b-814f-4360-875a-ad4c882285f5"));
				//car.setValue(next);
				//gatt.writeCharacteristic(car);
			}
			@Override
			public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
					int status) {
				System.err.println("here4 "+characteristic.getValue().length);
				msg.offer(characteristic.getValue());
			}

			
		});
		bluetoothGatt.requestMtu(mtu.get());
		
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

	
	public void stopInitiating(Activity test) {
				
	}

	
	public void shutdown(Activity test) {
		server.close();
	}

	
	public void startInitiating(Activity test) throws NfcLibException {
		System.err.println("start scanning");
		scanLeDevice(test);
		
	}
	
}
