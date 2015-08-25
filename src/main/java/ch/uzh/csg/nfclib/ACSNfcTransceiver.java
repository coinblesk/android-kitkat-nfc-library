package ch.uzh.csg.nfclib;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.acs.smartcard.Reader;
import com.acs.smartcard.Reader.OnStateChangeListener;
import com.acs.smartcard.ReaderException;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Pair;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcEvent;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcTransceiver;
import ch.uzh.csg.comm.TagDiscoverHandler;

/**
 * This class handles the ACR122u USB NFC reader initialization and the message
 * exchange over NFC.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class ACSNfcTransceiver implements NfcTrans {

	private static final Logger LOGGER = LoggerFactory.getLogger(ACSNfcTransceiver.class);

	/*
	 * 64 is the maximum due to a sequence bug in the ACR122u
	 * http://musclecard.996296
	 * .n3.nabble.com/ACR122U-response-frames-contain-wrong
	 * -sequence-numbers-td5002.html If larger than 64, then I get a
	 * com.acs.smartcard.CommunicationErrorException: The sequence number (4) is
	 * invalid.
	 * 
	 * The same problem arises sometimes even with the length of 54.
	 */
	protected static final int MAX_WRITE_LENGTH = 53;

	private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
	
	//private static final ReaderOpenCallback callback = new ReaderOpenCallback();
	
	/*private final Reader reader;
	
	private final IntentFilter filter;
	
	private final BroadcastReceiver broadcastReceiver;*/
	
	private final TagDiscoverHandler nfcInit;
	
	private BroadcastReceiver broadcastReceiver;
	private Reader reader;
	
	//hack, there is no way to chekc if a receiver is registered
	private volatile boolean broadcastReceiverRegistered = false;

	/**
	 * Creates a new instance.
	 * 
	 * @param eventHandler
	 *            the {@link NfcInitiatorHandler} (may not be null)
	 * @param nfcInit
	 *            the {@link TagDiscoveredHandler} which is notified as soon as
	 *            a NFC connection is established (may not be null)
	 * @throws NfcLibException 
	 */
	public ACSNfcTransceiver(final TagDiscoverHandler nfcInit, final Context context) {
		this.nfcInit = nfcInit;
	}
	
	private static void setOnStateChangedListener(final Reader reader, 
			final TagDiscoverHandler nfcInit, final ACSTransceiver transceiver) {
		
		if (Config.DEBUG) {
			LOGGER.debug( "set listener");
		}
		
		reader.setOnStateChangeListener(new OnStateChangeListener() {
			private boolean disabledBuzzer = false;
			public void onStateChange(int slotNum, int prevState, int currState) {
				if (Config.DEBUG) {
					LOGGER.debug( "statechange from: {} to: {}", prevState, currState);
				}			
				if (currState == Reader.CARD_PRESENT) {
					try {					
						transceiver.initCard(slotNum);
						if(!disabledBuzzer) {
							transceiver.disableBuzzer();
							disabledBuzzer = true;
						}
						nfcInit.tagDiscovered(transceiver, true, true);
					} catch (ReaderException e) {
						LOGGER.error( "Could not connnect reader (ReaderException): ", e);
						nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
					}
				} else if(currState == Reader.CARD_ABSENT) {
					nfcInit.tagLost(transceiver);
				}
			}
		});
	}
	

	/**
	 * Checks if the ACR122u USB NFC reader is attached via USB.
	 * 
	 * @param activity
	 *            the current activity, needed to retrieve all attached USB
	 *            devices
	 * @return true if the ACR122u USB NFC reader is attached, false otherwise
	 */
	public static boolean isExternalReaderAttached(Context context) {
		UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
		Reader reader = new Reader(manager);
		return externalReaderAttached(context, manager, reader) != null;
	}
	
	private static UsbDevice externalReaderAttached(Context context, UsbManager manager, Reader reader) {
		for (UsbDevice device : manager.getDeviceList().values()) {
			if (reader.isSupported(device)) {
				return device;
			}
		}
		return null;
	}
	
	public static Pair<ACSTransceiver, Reader> createReaderAndTransceiver(final Context context, /*final ReaderOpenCallback callback,*/ final TagDiscoverHandler nfcInit) throws NfcLibException {
		UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
		Reader reader = new Reader(manager);
		UsbDevice externalDevice = externalReaderAttached(context, manager, reader);
		if (externalDevice == null) {
			throw new NfcLibException("External device is not set");
		}

		//ask user for permission
		if(Config.DEBUG) {
			LOGGER.debug( "ask user for permission");
		}
		ACSTransceiver transceiver = new ACSTransceiver(reader, nfcInit);
		try {
			reader.open(externalDevice);
			
			//callback.readerOpen(reader, externalDevice, transceiver);
		} catch (IllegalArgumentException e) {
			if(Config.DEBUG) {
				LOGGER.debug( "could not access device, ask for permission", e);
			}
			PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
			manager.requestPermission(externalDevice, permissionIntent);	
		}
		
		return new Pair<ACSTransceiver, Reader>(transceiver, reader);
	}

	
	
	private static class ACSTransceiver implements NfcTransceiver {
			
		final private Reader reader;
		final private TagDiscoverHandler nfcInit;
		
		private ACSTransceiver(Reader reader, TagDiscoverHandler nfcInit) {
			this.reader = reader;
			this.nfcInit = nfcInit;
		}
		
		private void disableBuzzer() throws ReaderException {
			// Disable the standard buzzer when a tag is detected (Section 6.7). It sounds
			// immediately after placing a tag resulting in people lifting the tag off before
			// we've had a chance to read the ID.
			byte[] sendBuffer={(byte)0xFF, (byte)0x00, (byte)0x52, (byte)0x00, (byte)0x00};
			byte[] recvBuffer=new byte[8];
			int length = reader.transmit(0, sendBuffer, sendBuffer.length, recvBuffer, recvBuffer.length);
			if(length != 8) {
				nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
			}
		}
	
		private void initCard(final int slotNum) throws ReaderException {
			reader.power(slotNum, Reader.CARD_WARM_RESET);
			reader.setProtocol(slotNum, Reader.PROTOCOL_T0 | Reader.PROTOCOL_T1);
		}
		
		

		@Override
		public byte[] write(byte[] input) throws Exception {
			if (!reader.isOpened()) {
				if (Config.DEBUG) {
					LOGGER.debug( "could not write message, reader is not or no longe open");
				}
				throw new IOException(NFCTRANSCEIVER_NOT_CONNECTED);
			}

			if (input.length > MAX_WRITE_LENGTH) {
				throw new IOException("The message length exceeds the maximum capacity of " + MAX_WRITE_LENGTH + " bytes.");
			}

			final byte[] recvBuffer = new byte[MAX_WRITE_LENGTH];
			final int length;
			try {
				length = reader.transmit(0, input, input.length, recvBuffer, recvBuffer.length);
			} catch (ReaderException e) {
				if (Config.DEBUG) {
					LOGGER.debug( "could not write message - ReaderException", e);
				}
				throw new IOException(UNEXPECTED_ERROR);
			}

			if (length <= 0) {
				if (Config.DEBUG) {
					LOGGER.debug( "could not write message - return value is 0");
				}
				//most likely due to tag lost
				throw new IOException(UNEXPECTED_ERROR);
			}

			byte[] received = new byte[length];
			System.arraycopy(recvBuffer, 0, received, 0, length);
			return received;
		}

		@Override
		public int maxLen() {
			return MAX_WRITE_LENGTH;
		}

		@Override
		public void close() {
			System.err.println("do nothing");
			
		}
	}
	
	private static BroadcastReceiver createBroadcastReceiver(final Reader reader, final TagDiscoverHandler nfcInit,  /*final ReaderOpenCallback callback,*/ final ACSTransceiver transceiver) {
		return new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				
				if(Config.DEBUG) {
					LOGGER.debug( "actcion: {}", action);
				}

				if (ACTION_USB_PERMISSION.equals(action)) {
					if(Config.DEBUG) {
						LOGGER.debug( "try to create reader");
					}
					synchronized (this) {
						UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
						if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
							if (device != null) {
								try {
									if(Config.DEBUG) {
										LOGGER.debug( "reader open");
									}
									reader.open(device);
									//callback.readerOpen(reader, device, transceiver);
								} catch (Exception e) {
									nfcInit.tagFailed(NfcEvent.INIT_FAILED.name());
								}
							}
						}
					}
				} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
					synchronized (this) {
						UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

						if (device != null && device.equals(reader.getDevice())) {
							reader.close();
						}
						if(Config.DEBUG) {
							LOGGER.debug( "reader detached");
						}
					}
				}
			}

		};
	}

	@Override
	public int maxLen() {
		return MAX_WRITE_LENGTH;
	}
	
	@Override
	public boolean turnOn(Activity activity) {
		if(!broadcastReceiverRegistered) {
			if(Config.DEBUG) {
				LOGGER.debug( "turn on ACS");
			}
			
			
			
			final ACSTransceiver transceiver;
			try {
				Pair<ACSTransceiver, Reader> pair = createReaderAndTransceiver(activity/*, callback*/, nfcInit);
				transceiver = pair.first;
				reader = pair.second;
			} catch (NfcLibException e) {
				LOGGER.error( "reader not available", e);
				return false;
			}
			IntentFilter filter = new IntentFilter();
			filter.addAction(ACTION_USB_PERMISSION);
			filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
			
			broadcastReceiver = createBroadcastReceiver(reader, nfcInit/*, callback*/, transceiver);
			setOnStateChangedListener(reader, nfcInit, transceiver);
			activity.registerReceiver(broadcastReceiver, filter);
			broadcastReceiverRegistered = true;
		}
		return true;
	}

	@Override
	public void turnOff(Activity activity) {
		if(broadcastReceiverRegistered) {
			if(Config.DEBUG) {
				LOGGER.debug( "Turn off ACS: {}", broadcastReceiverRegistered);
			}
			
			broadcastReceiverRegistered = false;
			if (reader != null && reader.isOpened()) {
				reader.close();
				reader = null;
				if(Config.DEBUG) {
					LOGGER.debug( "Reader closed");
				}
			}
			activity.unregisterReceiver(broadcastReceiver);
		}
	}
	
	/*private static class ReaderOpenCallback  {
		//@Override
		public void readerOpen(Reader reader, UsbDevice externalDevice,  ACSTransceiver transceiver) {
			try {
				transceiver.disableBuzzer();
			} catch (ReaderException e) {
				LOGGER.error( "could not initialize transceiver", e);
			}
		}
	};*/
}
