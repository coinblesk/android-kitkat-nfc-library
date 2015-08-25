package ch.uzh.csg.btlib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.ParcelUuid;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcInitiator;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcTransceiver;

public class BTInitiatorSetup {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BTInitiatorSetup.class);
	// Stops scanning after 10 seconds.
	final private static long SCAN_PERIOD = 10 * 1000;
	
	final private Handler mHandler;
	final private BluetoothAdapter bluetoothAdapter;
	final private NfcInitiator initiator;
	final private NfcInitiatorHandler initiatorHandler;
	final private UUID localUUID;
	
	
    
    private static BTInitiatorSetup instance = null;
    
    public static BTInitiatorSetup init(final NfcInitiatorHandler initiatorHandler, 
    		final Activity activity, UUID localUUID, BluetoothAdapter bluetoothAdapter) {
    	if(instance == null) {
    		instance = new BTInitiatorSetup(initiatorHandler, localUUID, bluetoothAdapter);
    	}
    	return instance;
    }
	
	private BTInitiatorSetup(final NfcInitiatorHandler initiatorHandler, UUID localUUID, BluetoothAdapter bluetoothAdapter) {
		this.initiatorHandler = initiatorHandler;
		this.initiator = new NfcInitiator(initiatorHandler);
		this.bluetoothAdapter = bluetoothAdapter;
		this.mHandler = new Handler();
		this.localUUID = localUUID;
	}
	
	private void btleDiscovered(final NfcTransceiver nfcTransceiver) throws Exception {
		initiatorHandler.btleDiscovered(new BTLEController() {
			@Override
			public void startBTLE() {
				initiator.tagDiscoverHandler().tagDiscovered(nfcTransceiver, false, false);
			}
		});
		
	}
	
	public void scanLeDevice(final Activity activity, final UUID remoteUUID) {
		if(Config.DEBUG) {
			LOGGER.debug( "start scanning");
		}
		final BluetoothLeScanner sc = bluetoothAdapter.getBluetoothLeScanner();
		final ScanCallback scb = new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				// if(result.)
				if(Config.DEBUG) {
					LOGGER.debug( "scan result1: {}", callbackType);
					LOGGER.debug( "scan result2: {}", result.getDevice());
				}
				sc.stopScan(this);
				connect(activity, result.getDevice(), remoteUUID);
			}

			@Override
			public void onScanFailed(int errorCode) {
				LOGGER.error("scan failed: {}", errorCode);
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
		//this is currently the max value on Android
		final AtomicInteger mtu = new AtomicInteger(517);
		device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			BlockingQueue<byte[]> msg = new SynchronousQueue<>();
			
			@Override
			public void onMtuChanged(BluetoothGatt gatt, int mtu2, int status) {
				if(status != BluetoothGatt.GATT_SUCCESS) {
					mtu.set(mtu.get() / 2);
					if(Config.DEBUG) {
						LOGGER.debug( "mtu was *not* set to: {} go for: {}", mtu2, mtu.get());
					}
					tryRequestMtu(gatt, 10, mtu.get(), 100);
				} else {
					//continue
					initiator.setmaxTransceiveLength(mtu2);
					gatt.discoverServices();
					if(Config.DEBUG) {
						LOGGER.debug( "mtu was set to: {}", mtu2);
					}
				}
				
			}
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				if (newState == BluetoothGatt.STATE_CONNECTED) {
					gatt.setCharacteristicNotification(BTInitiatorSetup.this.getIndicateAddress(), true);
					tryRequestMtu(gatt, 10, mtu.get(), 100);
			    }
			}
			
			private void tryRequestMtu(final BluetoothGatt gatt, final int nr, final int mtu, final int sleepMillis) {
				mHandler.post(new Runnable() {				
					@Override
					public void run() {
						for(int i=0;i<nr;i++) {
							if(gatt.requestMtu(mtu)) {
								if(Config.DEBUG) {
									LOGGER.debug( "mtu success");
								}
								break;
							}
							if(Config.DEBUG) {
								LOGGER.debug( "mtu failed");
							}
							try {
								Thread.sleep(sleepMillis);
							} catch (InterruptedException e) {
								break;
							}
						}
					}
				});
				
			}
			
			@Override
			public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
				if(Config.DEBUG) {
					LOGGER.debug( "device discovered");
				}
				if (status == BluetoothGatt.GATT_SUCCESS) {
					if(Config.DEBUG) {
						LOGGER.debug( "service: {}", gatt.getServices());
					}
					
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
									boolean retVal = gatt.writeCharacteristic(car);
									if(Config.DEBUG) {
										LOGGER.debug( "wrote characteristic: {}", retVal);
									}
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
				if(Config.DEBUG) {
					LOGGER.debug( "got reply back characteristic: {}", Arrays.toString(characteristic.getValue()));
				}
				msg.offer(characteristic.getValue());
			}	
		});
	}
	
	public BluetoothGattCharacteristic getIndicateAddress() {
		final BluetoothGattCharacteristic characteristicIndicate =
		        new BluetoothGattCharacteristic(localUUID,
		                BluetoothGattCharacteristic.PROPERTY_INDICATE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
		                BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);
		return characteristicIndicate;
	}
}
