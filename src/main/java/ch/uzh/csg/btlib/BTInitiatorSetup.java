package ch.uzh.csg.btlib;

import java.io.IOException;
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
import android.bluetooth.BluetoothGattDescriptor;
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
import ch.uzh.csg.comm.NfcMessage;
import ch.uzh.csg.comm.NfcTransceiver;

public class BTInitiatorSetup {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BTInitiatorSetup.class);
	// Stops scanning after 10 seconds.
	final private static long SCAN_PERIOD = 10 * 1000;
	
	final private Handler mHandler;
	final private BluetoothAdapter bluetoothAdapter;
	final private NfcInitiator initiator;
	final private NfcInitiatorHandler initiatorHandler;
	//final private UUID localUUID;
	
	
    
    private static BTInitiatorSetup instance = null;
    
    public static BTInitiatorSetup init(final NfcInitiator initiator, 
    		final Activity activity,  BluetoothAdapter bluetoothAdapter) {
    	if(instance == null) {
    		instance = new BTInitiatorSetup(initiator, bluetoothAdapter);
    	}
    	return instance;
    }
	
	private BTInitiatorSetup(final NfcInitiator initiator, BluetoothAdapter bluetoothAdapter) {
		this.initiatorHandler = initiator.getInitiatorHandler();
		this.initiator = initiator;
		this.bluetoothAdapter = bluetoothAdapter;
		this.mHandler = new Handler();
	}
	
	private void btleDiscovered(final NfcTransceiver nfcTransceiver) throws Exception {
		initiatorHandler.btleDiscovered(new BTLEController() {
			@Override
			public void startBTLE() {
				if(Config.DEBUG) {
					LOGGER.debug( "start BT");
				}
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
		scf.add(new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(remoteUUID.toString())).build());
		sc.startScan(scf, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scb);

	}
	
	//we must scan and cannot call connect directly
	private void connect(Activity activity, BluetoothDevice device, final UUID remoteUUID) {
		//this is currently the max value on Android
		final AtomicInteger mtu = new AtomicInteger(1034);
		final AtomicInteger seq = new AtomicInteger(0);
		device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			private BlockingQueue<byte[]> msg = new SynchronousQueue<>();
			private BluetoothGattCharacteristic car = null;
			
			@Override
			public void onMtuChanged(BluetoothGatt gatt, int mtu2, int status) {
				if(status != BluetoothGatt.GATT_SUCCESS) {
					mtu.set(mtu.get() / 2);
					if(Config.DEBUG) {
						LOGGER.debug( "mtu was *not* set to: {} go for: {}", mtu2, mtu.get());
					}
				} else {
					//continue
					if(Config.DEBUG) {
						LOGGER.debug( "mtu was set to: {}", mtu2);
					}
				}
				initiator.setmaxTransceiveLength(mtu2);
				gatt.discoverServices();
				
			}
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				if (newState == BluetoothGatt.STATE_CONNECTED) {
					gatt.requestMtu(517);
			    }
			}
			
			//negotiating is not working currently
			/*private void tryRequestMtu(final BluetoothGatt gatt, final int nr, final int mtu, final int sleepMillis) {
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
				
			}*/
			
			@Override
			public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
				if(Config.DEBUG) {
					LOGGER.debug( "device discovered");
				}
				
				if (status == BluetoothGatt.GATT_SUCCESS) {
					if(Config.DEBUG) {
						LOGGER.debug( "service: {}", gatt.getServices());
					}
					
					final BluetoothGattService ser = gatt.getService(remoteUUID);
					car = ser.getCharacteristic(BTResponderSetup.COINBLESK_CHARACTERISTIC_UUID);
					//enable indication, see
					//http://stackoverflow.com/questions/27068673/subscribe-to-a-ble-gatt-notification-android
					//http://developer.android.com/guide/topics/connectivity/bluetooth-le.html#notification
					/*gatt.setCharacteristicNotification(car, true);
					UUID uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
					BluetoothGattDescriptor descriptor = car.getDescriptor(uuid);
					descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
					boolean retVal = gatt.writeDescriptor(descriptor);
					if(Config.DEBUG) {
						LOGGER.debug( "enable indication: {}", retVal);
					}*/
					
					new Thread(new Runnable() {
						
						@Override
						public void run() {
							try {
							btleDiscovered(new NfcTransceiver() {
								
								@Override
								public byte[] write(byte[] input) throws Exception {
									if(car == null) {
										throw new IOException("Characteristic is null");
									}
									car.setValue(input);
									seq.set(NfcMessage.sequence(input));
									for(int i=0;i<10;i++) {
										if(gatt.writeCharacteristic(car)) {
											if(Config.DEBUG) {
												LOGGER.debug( "wrote characteristic: success");
												//boolean retVal = gatt.readCharacteristic(car);
												//LOGGER.debug( "read characteristic: {}", retVal);
											}
											break;
										} else {
											if(Config.DEBUG) {
												LOGGER.debug( "wrote characteristic: failed");
											}
											Thread.sleep(100);
										}
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
					
				} else {		       
					car = null;
				}
			}
			
			@Override
			public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
				if(Config.DEBUG) {
					LOGGER.debug( "subscribtion request done: {}", status);
				}
				//car.setValue(NfcMessage.BTLE_INIT);
				
			}
			
			@Override
			public void onCharacteristicWrite(BluetoothGatt gatt,
					BluetoothGattCharacteristic characteristic, int status) {
				if(Config.DEBUG) {
					LOGGER.debug( "characteristic request done: {}", status);
				}
				
				boolean retVal = gatt.readCharacteristic(car);
				LOGGER.debug( "read characteristic: {}", retVal);
			}
			@Override
			public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
					int status) {
				if(Config.DEBUG) {
					LOGGER.debug("on read: "+characteristic.getValue().length);
				}
				msg.offer(characteristic.getValue());
			}
			
			@Override
			public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
				if(Config.DEBUG) {
					LOGGER.debug( "got reply back characteristic: {}", Arrays.toString(characteristic.getValue()));
				}
				msg.offer(characteristic.getValue());
			}
		});
	}
	
	/*public BluetoothGattCharacteristic getIndicateAddress() {
		final BluetoothGattCharacteristic characteristicIndicate =
		        new BluetoothGattCharacteristic(localUUID,
		                BluetoothGattCharacteristic.PROPERTY_INDICATE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
		                BluetoothGattCharacteristic.PERMISSION_WRITE | BluetoothGattCharacteristic.PERMISSION_READ);
		return characteristicIndicate;
	}*/
}
