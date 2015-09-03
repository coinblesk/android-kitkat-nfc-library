package ch.uzh.csg.btlib;

import java.util.ArrayList;
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
import ch.uzh.csg.comm.NfcMessage;
import ch.uzh.csg.comm.NfcMessage.Type;
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
	
	//status that will not occur here are repurposed
	//don't use permission or encryption, or the device will get bonded automatically:
	//http://stackoverflow.com/questions/24645519/android-how-can-i-make-ble-device-to-paired-device-bonded
	public static final int GET_NEXT_FRAGMENT = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
	public static final int POLLING_RESPONSE = BluetoothGatt.GATT_INVALID_OFFSET;
	public static final int POLLING_REQUEST = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
	
	public static final int INITIAL_SIZE = 517;
	public static final int BT_OVERHEAD = 3;
	final private AtomicInteger mtu = new AtomicInteger(INITIAL_SIZE);
    
    private static BTInitiatorSetup instance = null;
    
    private BluetoothGatt gatt;
    
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
	
	private void btleDiscovered(final NfcTransceiver nfcTransceiver) {
		initiatorHandler.btTagFound(new BTLEController() {
			@Override
			public void startBTLE() {
				if(Config.DEBUG) {
					LOGGER.debug( "start BT");
				}
				initiator.setmaxTransceiveLength(mtu.get() - BT_OVERHEAD);
				initiator.tagDiscoverHandler().tagDiscovered(nfcTransceiver, false, false);
				
			}
		});
		
	}
	
	public boolean isOpen() {
		return gatt!=null;
	}
	
	public void close() {
		if(Config.DEBUG) {
			LOGGER.debug( "try close device");
		}
		if(gatt!=null) {
			gatt.close();
			if(Config.DEBUG) {
				LOGGER.debug( "device closed");
			}
			gatt=null;
		}
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
					LOGGER.debug( "scan result1: {}, {}", callbackType, result.getDevice());
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
		final AtomicInteger seq = new AtomicInteger(0);
		device.connectGatt(activity, false, new BluetoothGattCallback() {
			
			private BlockingQueue<byte[]> msg = new SynchronousQueue<>();
			private BluetoothGattCharacteristic carClassic = null;
			private BluetoothGattCharacteristic carFastRead = null;
			
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
					mtu.set(mtu2);
				}
				gatt.discoverServices();
				
			}
			
			@Override
			public void onConnectionStateChange(BluetoothGatt gatt, int status,
					int newState) {
				if (newState == BluetoothGatt.STATE_CONNECTED) {
					if(Config.DEBUG) {
						LOGGER.debug( "connected");
					}
					BTInitiatorSetup.this.gatt = gatt;
					gatt.requestMtu(mtu.get());
			    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
			    	if(Config.DEBUG) {
						LOGGER.debug( "disconnected");
					}
			    	initiatorHandler.btTagLost();
			    	BTInitiatorSetup.this.gatt = null;
			    }
			}
			
			//TODO: negotiating is not working currently, BT is crashing on the attempt
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
					carClassic = ser.getCharacteristic(BTResponderSetup.COINBLESK_CHARACTERISTIC_UUID_CLASSIC);
					if(carClassic == null) {
						initiator.getInitiatorHandler().handleFailed("Characteristic is null");
						return;
					}
					
					//optional
					carFastRead = ser.getCharacteristic(BTResponderSetup.COINBLESK_CHARACTERISTIC_UUID_FAST_READ);
					if(Config.DEBUG) {
						LOGGER.debug( "read fast characteristic init: {}", carFastRead);
					}
					
					btleDiscovered(new NfcTransceiver() {

						@Override
						public byte[] write(byte[] input) throws Exception {
							seq.set(NfcMessage.sequence(input));
							if(carFastRead != null && NfcMessage.type(input) == Type.FRAGMENT && NfcMessage.isEmpty(input)) {
								if(Config.DEBUG) {
									LOGGER.debug( "read fast characteristic");
								}
								boolean read = gatt.readCharacteristic(carFastRead);
								if(Config.DEBUG) {
									LOGGER.debug( "read fast characteristic: {}", read);
								}
								if(!read) {
									initiatorHandler.btTagLost();
									return null;
								}
							} else {
								carClassic.setValue(input);
								if(Config.DEBUG) {
									LOGGER.debug( "wrote characteristic");
								}
								boolean write = gatt.writeCharacteristic(carClassic);
								if(Config.DEBUG) {
									LOGGER.debug( "wrote characteristic: {}", write);
								}
								if(!write) {
									initiatorHandler.btTagLost();
									return null;
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
					
				} else {		       
					initiator.getInitiatorHandler().handleFailed("Characteristic not discovered: " + status);
				}
			}
			
			/*@Override
			public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
				if(Config.DEBUG) {
					LOGGER.debug( "subscribtion request done: {}", status);
				}
				//car.setValue(NfcMessage.BTLE_INIT);
				
			}*/
			
			@Override
			public void onCharacteristicWrite(BluetoothGatt gatt,
					BluetoothGattCharacteristic characteristic, int status) {
				if(Config.DEBUG) {
					LOGGER.debug( "characteristic request done: {}, {}, {}", status, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, BluetoothGatt.GATT_SUCCESS);
				}
				
				if(status == GET_NEXT_FRAGMENT) {
					NfcMessage m = new NfcMessage(Type.FRAGMENT);
					m.sequenceNumber(seq.get());
					msg.offer(m.bytes());
				} else if(status == POLLING_RESPONSE) {
					NfcMessage m = new NfcMessage(Type.POLLING_RESPONSE);
					m.sequenceNumber(seq.get());
					msg.offer(m.bytes());
				} else if(status == POLLING_REQUEST) {
					NfcMessage m = new NfcMessage(Type.POLLING_REQUEST);
					m.sequenceNumber(seq.get());
					msg.offer(m.bytes());
				} else {
					boolean retVal = gatt.readCharacteristic(carClassic);
					LOGGER.debug( "read characteristic: {}", retVal);
				}
			}
			@Override
			public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
					int status) {
				if(Config.DEBUG) {
					LOGGER.debug("on read: "+characteristic.getValue().length);
				}
				msg.offer(characteristic.getValue());
			}
			
			/*@Override
			public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
				if(Config.DEBUG) {
					LOGGER.debug( "got reply back characteristic: {}", Arrays.toString(characteristic.getValue()));
				}
				msg.offer(characteristic.getValue());
			}*/
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
