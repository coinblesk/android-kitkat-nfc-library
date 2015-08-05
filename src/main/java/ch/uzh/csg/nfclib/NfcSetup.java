package ch.uzh.csg.nfclib;

import java.io.IOException;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import ch.uzh.csg.comm.CommSetup;
import ch.uzh.csg.comm.Config;
import ch.uzh.csg.comm.NfcEvent;
import ch.uzh.csg.comm.NfcInitiatorHandler;
import ch.uzh.csg.comm.NfcLibException;
import ch.uzh.csg.comm.NfcMessage;
import ch.uzh.csg.comm.NfcMessageSplitter;
import ch.uzh.csg.comm.NfcResponder;
import ch.uzh.csg.comm.NfcResponseHandler;
import ch.uzh.csg.comm.NfcTransceiver;
import ch.uzh.csg.comm.TagDiscoverHandler;
import ch.uzh.csg.comm.Utils;
import ch.uzh.csg.comm.NfcMessage.Type;

/**
 * This class represents the NFC party which initiates a NFC connection. It
 * sends a request and receives a response from the {@link NfcResponder}. This
 * can be repeated as often as required.
 * 
 * To be able to send and receive messages, enable() has to be called first.
 * Afterwards, transceive(byte[]) can be called. Once all messages are
 * exchanged, disable() has to be called in order to stop the services
 * appropriately.
 * 
 * Packet flow (handshake):
 * sender -> recipient
 * AID ->
 * <- AID_SELECTED
 * -> USER_ID
 * <- USER_ID
 * = handshake complete
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class NfcSetup implements CommSetup {
	private static final String TAG = "ch.uzh.csg.nfclib.NfcSetup";
	
	public static final String NULL_ARGUMENT = "The message is null";
	public static final String NFCTRANSCEIVER_NOT_CONNECTED = "Could not write message, NfcTransceiver is not connected.";
	public static final String UNEXPECTED_ERROR = "An error occured while transceiving the message.";
	public static final String INVALID_SEQUENCE = "Invalid sequence";
	public static final String TIMEOUT = "Timeout";

	private final NfcTrans transceiver;
	
	private final NfcInitiatorHandler initiatorHandler;
	private final AppBroadcastReceiver broadcastReceiver;

	// state
	private final Deque<NfcMessage> messageQueue = new ConcurrentLinkedDeque<NfcMessage>();
	private final NfcMessageSplitter messageSplitter = new NfcMessageSplitter();
	private NfcMessage lastMessageSent;
	
	private volatile boolean initiating = true;
	
	final public class AppBroadcastReceiver extends BroadcastReceiver {
		
		final NfcResponder responder;
		public AppBroadcastReceiver(final NfcResponder responder) {
			this.responder = responder;
		}
		
        @Override
        public void onReceive(final Context context, final Intent intent) {
        	if(Config.DEBUG) {
        		Log.d(TAG, "received broadcast message "+intent);
        	}
        	final byte[] responseApdu = intent.getExtras().getByteArray(HostApduServiceNfcLib.NFC_SERVICE_SEND_DATA);
        	if(responseApdu != null) {
        		byte[] processed = responder.processIncomingData(responseApdu);
        		sendBroadcast(context, processed);
        	} else {
        		final int reason = intent.getExtras().getInt(HostApduServiceNfcLib.NFC_SERVICE_SEND_DEACTIVATE);
        		responder.onDeactivated(reason);
        	}
        	
        }
        
        private void sendBroadcast(Context context, final byte[] bytes) {
    		final Intent intent = new Intent(HostApduServiceNfcLib.NFC_SERVICE_RECEIVE_INTENT);
    	    intent.putExtra(HostApduServiceNfcLib.NFC_SERVICE_RECEIVE_DATA, bytes);
    	    context.sendBroadcast(intent);
    	}
    };

	/**
	 * Instantiates a new object. If the ACR122u USB NFC reader is attached, it
	 * will be used for the NFC. Otherwise, the build-in NFC controller will be
	 * used.
	 * 
	 * @param eventHandler
	 *            the {@link NfcInitiatorHandler} to listen for {@link NfcEvent}s
	 * @param activity
	 *            the application's current activity to bind the NFC service to
	 *            it
	 * @param userId
	 *            the identifier of this user (or this mobile device)
	 * @throws NfcLibException 
	 */
	public NfcSetup(final NfcInitiatorHandler initiatorHandler, final NfcResponseHandler responseHandler, final Activity activity) throws NfcLibException {
		this.initiatorHandler = initiatorHandler;
		if (hasClass("com.acs.smartcard.Reader") && ACSNfcTransceiver.isExternalReaderAttached(activity)) {
			transceiver = new ACSNfcTransceiver(tagDiscoverHandler(activity), activity);
		} else {
			transceiver = new AndroidNfcTransceiver(tagDiscoverHandler(activity), activity);
		}
		
		final NfcResponder responder = new NfcResponder(responseHandler, transceiver.maxLen());
		
		IntentFilter filter = new IntentFilter();
		filter.addAction(HostApduServiceNfcLib.NFC_SERVICE_SEND_INTENT);
		
		broadcastReceiver = new AppBroadcastReceiver(responder);
		activity.registerReceiver(broadcastReceiver, filter);
	}
	
	/**
	 * This class initializes the {@link NfcSetup} as soon as a NFC tag has
	 * been discovered.
	 */
	

	protected TagDiscoverHandler tagDiscoverHandler(final Activity activity) {
		
		return new TagDiscoverHandler() {
			@Override
			public void tagDiscovered(final NfcTransceiver nfcTransceiver) {
				if (Config.DEBUG) {
					Log.d(TAG, "Tag detected!");
				}
				try {
					if (!initiating) {
						if (Config.DEBUG) {
							Log.d(TAG, "Nothing to do shutdown!");
						}
						nfcTransceiver.close();
						return;
					}
					try {
						handshake(nfcTransceiver);
					} catch (IOException e) {
						initiatorHandler.handleFailed(e.toString());
						return;
					}
					initiatorHandler.handleStatus("handshake complete");
					// check if we should resume
					if (!messageQueue.isEmpty()) {
						if (!processMessage(nfcTransceiver)) {
							return;
						}
					}
					// get the complete message
					while (initiatorHandler.hasMoreMessages()) {
						boolean first = initiatorHandler.isFirst();
						byte[] message = initiatorHandler.nextMessage();
						if (message == null) {
							//start polling
							messageQueue.offer(new NfcMessage(Type.POLLING_REQUEST));
						} else {

							// split it
							for (NfcMessage msg : messageSplitter.getFragments(message)) {
								messageQueue.offer(msg);
							}
						}

						if (!processMessage(nfcTransceiver)) {
							return;
						}

					}

					// we are complete

					// hack for PN547 devices
					//Oneplus One with PN547: nfaDeviceManagementCallback: crash NFC service: happens often in initiator mode:
					
//					F/libc    (31968): Fatal signal 6 (SIGABRT), code -6 in tid 31992 (Thread-2661)
//					I/DEBUG   (  270): *** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***
//					I/DEBUG   (  270): Build fingerprint: 'oneplus/bacon/A0001:5.0.2/LRX22G/YNG1TAS2I3:user/release-keys'
//					I/DEBUG   (  270): Revision: '0'
//					I/DEBUG   (  270): ABI: 'arm'
//					I/DEBUG   (  270): pid: 31968, tid: 31992, name: Thread-2661  >>> com.android.nfc <<<
//					E/DEBUG   (  270): AM write failure (32 / Broken pipe)
//					I/DEBUG   (  270): signal 6 (SIGABRT), code -6 (SI_TKILL), fault addr --------
//					I/DEBUG   (  270):     r0 00000000  r1 00007cf8  r2 00000006  r3 00000000
//					I/DEBUG   (  270):     r4 a2e01db8  r5 00000006  r6 00000000  r7 0000010c
//					I/DEBUG   (  270):     r8 a2e01ca0  r9 ffffff34  sl b6f2a9fd  fp a2e01db0
//					I/DEBUG   (  270):     ip 00007cf8  sp a2e01b58  lr b6f2b235  pc b6f4de44  cpsr 600f0010
//					I/DEBUG   (  270): 
//					I/DEBUG   (  270): backtrace:
//					I/DEBUG   (  270):     #00 pc 00036e44  /system/lib/libc.so (tgkill+12)
//					I/DEBUG   (  270):     #01 pc 00014231  /system/lib/libc.so (pthread_kill+52)
//					I/DEBUG   (  270):     #02 pc 00014f93  /system/lib/libc.so (raise+10)
//					I/DEBUG   (  270):     #03 pc 000116a5  /system/lib/libc.so (__libc_android_abort+36)
//					I/DEBUG   (  270):     #04 pc 0000fd3c  /system/lib/libc.so (abort+4)
//					I/DEBUG   (  270):     #05 pc 0000d761  /system/lib/libnfc_nci_jni.so
//					I/DEBUG   (  270):     #06 pc 0001aed0  /system/lib/libnfc-nci.so
//					I/DEBUG   (  270):     #07 pc 0003d940  /system/lib/libnfc-nci.so (nfc_ncif_event_status+64)
//					I/DEBUG   (  270):     #08 pc 0003d9a0  /system/lib/libnfc-nci.so (nfc_ncif_cmd_timeout+52)
//					I/DEBUG   (  270):     #09 pc 0003caf8  /system/lib/libnfc-nci.so (nfc_process_timer_evt+120)
//					I/DEBUG   (  270):     #10 pc 0003d000  /system/lib/libnfc-nci.so (nfc_task+412)
//					I/DEBUG   (  270):     #11 pc 00058630  /system/lib/libnfc-nci.so (gki_task_entry+48)
//					I/DEBUG   (  270):     #12 pc 00013a1b  /system/lib/libc.so (__pthread_start(void*)+30)
//					I/DEBUG   (  270):     #13 pc 00011a0f  /system/lib/libc.so (__start_thread+6)
//					I/DEBUG   (  270): 
//					I/DEBUG   (  270): Tombstone written to: /data/tombstones/tombstone_01
					try {
						while (true) {
							NfcMessage request = new NfcMessage(Type.POLLING_REQUEST);
							request.sequenceNumber(lastMessageSent);
							nfcTransceiver.write(request.bytes());
							lastMessageSent = request; 
						}
					} catch (NfcLibException e) {
						initiating = false;
						if (Config.DEBUG) {
							Log.d(TAG, "loop done");
						}
						reset();
					}

				} catch (Throwable t) {
					t.printStackTrace();
					initiatorHandler.handleFailed(t.toString());
					return;
				}

			}
			
			@Override
			public void tagFailed(String message) {
				initiatorHandler.handleFailed(message);
			}

			@Override
			public void tagLost(NfcTransceiver nfcTransceiver) {
				/*if(!initiating) {
					transceiver.turnOff(activity);
				}*/
				System.err.println("TG LOST");
				
			}
		};
	}
	
	private boolean processMessage(NfcTransceiver transceiver) {
		try {
			messageLoop(transceiver);
		} catch (Throwable t) {
			t.printStackTrace();
			reset();
			initiatorHandler.handleFailed(t.toString());
			return false;
		}
		messageSplitter.clear();
		return true;
	}
	
	private void reset() {
		lastMessageSent = null;
		messageQueue.clear();
		messageSplitter.clear();
	}
	
	private void handshake(NfcTransceiver transceiver) throws Exception {
		if (Config.DEBUG) {
			Log.d(TAG, "init NFC");
		}
		final NfcMessage initMessage;
		final int maxLenThis = transceiver.maxLen();
		switch(maxLenThis) {
		case 53:
			initMessage = new NfcMessage(Type.AID_2);
			break;
		case 245:
			initMessage = new NfcMessage(Type.AID_3);
			break;
		case Integer.MAX_VALUE:
			initMessage = new NfcMessage(Type.AID_1);
			break;
		default:
			throw new IOException(NfcEvent.INIT_FAILED.name());
		}
		
		// no sequence number here,initiating.set( as this is a special message
		if (Config.DEBUG) {
			Log.d(TAG, "handshake write: "+Arrays.toString(initMessage.bytes()));
		}
		final byte[] response = transceiver.write(initMessage.bytes());
		final NfcMessage responseMessage = new NfcMessage(response);
		if (Config.DEBUG) {
			Log.d(TAG, "handshake response: "+Arrays.toString(response));
		}
		// --> here we can get an exception. We should get back this array: {2,0,0,0,x}
		if (responseMessage.sequenceNumber() != 0) {
			if (Config.DEBUG) {
				Log.e(TAG, "handshake header unexpected: " + responseMessage);
			}
			throw new IOException(NfcEvent.INIT_FAILED.name());
		}
		if (responseMessage.payload().length != 4) {
			if (Config.DEBUG) {
				Log.e(TAG, "handshake payload unexpected: " + responseMessage);
			}
			throw new IOException(NfcEvent.INIT_FAILED.name());
		}
		final int maxLenOther = Utils.byteArrayToInt(responseMessage.payload(), 0);
		messageSplitter.maxTransceiveLength(Math.min(maxLenOther, maxLenThis));
	}
	
	private void messageLoop(NfcTransceiver transceiver) throws Exception {
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
				throw new IOException(UNEXPECTED_ERROR);
			}
			NfcMessage responseMessage = new NfcMessage(response);
			if (Config.DEBUG) {
				Log.d(TAG, "loop response: "+responseMessage);
			}
			
			//message successfully sent, remove from queue
			messageQueue.poll();
			initiatorHandler.handleStatus("message fragment sent, queue: "+messageQueue.size());
			
			
			if (!validateSequence(request, responseMessage)) {
				if (Config.DEBUG) {
					Log.e(TAG, "sequence error " + request + " / " + response);
				}
				throw new IOException(INVALID_SEQUENCE);
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
			case POLLING_REQUEST:
				messageQueue.offer(new NfcMessage(Type.POLLING_RESPONSE));
				break;
			case POLLING_RESPONSE:
				break;
			case ERROR:
				throw new IOException("the message "+request+" caused an exception on the other side");
			default:
				throw new IOException("did not expect the type "+responseMessage.type()+" as reply");
			}
		}
	}
	
	public void shutdown(Activity activity) {
		activity.unregisterReceiver(broadcastReceiver);
	}

	/**
	 * Enables the NFC so that messages can be exchanged. Attention: the
	 * enable() method must be called first!
	 * @throws NfcLibException 
	 */
	public void startInitiating(Activity activity) throws NfcLibException {
		reset();
		transceiver.turnOn(activity);
		initiating = true;
	}
	
	/**
	 * Soft disables the NFC to prevent devices such as the Samsung Galaxy Note
	 * 3 (other devices may show the same behavior!) to restart the protocol
	 * after having send the last message!
	 * 
	 * This should be called after a successful communication. Once you want to
	 * restart the NFC capability, call enableNFC.
	 */
	public void stopInitiating(Activity activity) {
		initiating = false;
		transceiver.turnOff(activity);
	}
	
	public static boolean validateSequence(final NfcMessage request, final NfcMessage response) {
		boolean check = request.sequenceNumber() == response.sequenceNumber();
		if (!check) {
			if (Config.DEBUG) {
				Log.e(TAG, "sequence number mismatch, expected " + ((request.sequenceNumber() + 1) % 255) + ", but was: " + response.sequenceNumber());
			}
			return false;
		}
		return true;
	}
	
	private static boolean hasClass(String className) {
	    try  {
	        Class.forName(className);
	        return true;
	    }  catch (final ClassNotFoundException e) {
	        return false;
	    }
	}
}
