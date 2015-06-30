package ch.uzh.csg.nfclib;

import java.io.IOException;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import ch.uzh.csg.nfclib.NfcMessage.Type;

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
public class NfcSetup {
	private static final String TAG = "ch.uzh.csg.nfclib.NfcSetup";
	
	public static final int CONNECTION_TIMEOUT = 500;
	public static final String NULL_ARGUMENT = "The message is null";
	public static final String NFCTRANSCEIVER_NOT_CONNECTED = "Could not write message, NfcTransceiver is not connected.";
	public static final String UNEXPECTED_ERROR = "An error occured while transceiving the message.";
	public static final String INVALID_SEQUENCE = "Invalid sequence";
	public static final String TIMEOUT = "Timeout";

	private final NfcTransceiver transceiver;
	
	private final NfcInitiatorHandler initiatorHandler;
	private final AppBroadcastReceiver broadcastReceiver;

	// state
	private final Deque<NfcMessage> messageQueue = new ConcurrentLinkedDeque<NfcMessage>();
	private final NfcMessageSplitter messageSplitter = new NfcMessageSplitter();
	private NfcMessage lastMessageSent;
	// if the task is null, it means either we did not start or we are done.
	private ExecutorService executorService = Executors.newSingleThreadExecutor();;
	
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
        		executorService.submit(new Runnable() {
        			@Override
        			public void run() {
        				byte[] processed = responder.processIncomingData(responseApdu);
        				sendBroadcast(context, processed);
        			}
        		});
        	} else {
        		final int reason = intent.getExtras().getInt(HostApduServiceNfcLib.NFC_SERVICE_SEND_DEACTIVATE);
        		executorService.submit(new Runnable() {
        			@Override
        			public void run() {
        				responder.onDeactivated(reason);
        			}
        		});
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
	 */
	public NfcSetup(final NfcInitiatorHandler initiatorHandler, final NfcResponseHandler responseHandler, final Activity activity) {
		this.initiatorHandler = initiatorHandler;
		if (hasClass("com.acs.smartcard.Reader") && ACSNfcTransceiver.isExternalReaderAttached(activity)) {
			transceiver = new ACSNfcTransceiver(tagDiscoverHandler());
		} else {
			transceiver = new AndroidNfcTransceiver(tagDiscoverHandler());
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
	

	protected TagDiscoverHandler tagDiscoverHandler() {
		return new TagDiscoverHandler() {
			@Override
			public void tagDiscovered(final NfcTransceiver nfcTransceiver) {
				executorService.submit(new Runnable() {
					@Override
					public void run() {
						try {
							try {
								handshake();
							} catch (IOException e) {
								initiatorHandler.handleFailed(e.toString());
								return;
							}
							initiatorHandler.handleStatus("handshake complete");
							//check if we should resume
							if(!messageQueue.isEmpty()) {
								messageQueue.peek().resume();
								if(!processMessage()) {
									return;
								}
							}
							//get the complete message
							while (initiatorHandler.hasMoreMessages()) {
								boolean last = initiatorHandler.isLast();
								boolean first = initiatorHandler.isFirst();
								byte[] message = initiatorHandler.nextMessage();
								if(message == null) {
									initiatorHandler.handleFailed("noting to do");
									return;
								}
								
								// split it
								for (NfcMessage msg : messageSplitter.getFragments(message, first, last)) {
									messageQueue.offer(msg);
								}
								
								if(!processMessage()) {
									return;
								}
								
							}
							//we are complete
							transceiver.stopInitiating();
							if (Config.DEBUG) {
								Log.d(TAG, "loop done");
							}
							reset();
							
						} catch (Throwable t) {
							t.printStackTrace();
							initiatorHandler.handleFailed(t.toString());
							return;
						}	
					}
				});
			}
			
			@Override
			public void tagFailed(String message) {
				initiatorHandler.handleFailed(message);
			}
		};
	}
	
	private boolean processMessage() {
		try {
			messageLoop();
		} catch (Throwable t) {
			t.printStackTrace();
			initiatorHandler.handleFailed(t.toString());
			return false;
		}
		try {
			initiatorHandler.handleMessageReceived(messageSplitter.data());
		} catch (Throwable t) {
			t.printStackTrace();
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
	
	private void handshake() throws IOException {
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
		
		// no sequence number here, as this is a special message
		if (Config.DEBUG) {
			Log.d(TAG, "handshake write: "+Arrays.toString(initMessage.bytes()));
		}
		final byte[] response = transceiver.write(initMessage.bytes());
		final NfcMessage responseMessage = new NfcMessage(response);
		if (Config.DEBUG) {
			Log.d(TAG, "handshake response: "+Arrays.toString(response));
		}
		// --> here we can get an exception. We should get back this array: {2,0,0,0,0,x}
		if (!responseMessage.isSelectAidApdu() || responseMessage.sequenceNumber() != 0 || responseMessage.isResume()) {
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
	
	private void messageLoop() throws IOException {
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
			
			if(!responseMessage.isGetNextFragment()) {
				messageSplitter.reassemble(responseMessage);
			} else if(responseMessage.isFragment()) {
				messageQueue.offer(new NfcMessage(Type.FRAGMENT));
			} else if (responseMessage.type() == Type.POLLING) {
				messageQueue.offer(new NfcMessage(Type.POLLING));
			} else if (responseMessage.type() == Type.FRAGMENT && !messageQueue.isEmpty()) {
				if (Config.DEBUG) {
					Log.d(TAG, "got fragment, continue sending " + request + " / " + response);
				}
			} else if (responseMessage.type() == Type.DONE && messageQueue.isEmpty()) {
				if (Config.DEBUG) {
					Log.d(TAG, "got message, stop sending " + request + " / " + response);
				}
			}
			else {
				throw new IOException(UNEXPECTED_ERROR);
			}
		}
	}

	/**
	 * Binds the NFC service to this activity and initializes the NFC features.
	 * In order to fully initialize the NFC, call enableNFC(). Otherwise, no
	 * messages will be exchanged.
	 * 
	 * @param activity
	 *            the application's current activity (may not be null)
	 */
	public void turnOn(Activity activity) {
		
		try {
			transceiver.turnOn(activity);
		} catch (NfcLibException e) {
			if (Config.DEBUG)
				Log.e(TAG, "enable failed: ", e);
		}
	}

	/**
	 * Unbinds the NFC service from this activity and releases it for other
	 * applications.
	 * 
	 * @param activity
	 *            the application's current activity (may not be null)
	 */
	public void turnOff(Activity activity) {
		
		if (Config.DEBUG) {
			Log.e(TAG, "turn off transceiver");
		}
		transceiver.turnOff(activity);
	}
	
	public void shutdown(Activity activity) {
		if (executorService != null) {
			executorService.shutdown();
			try {
				executorService.awaitTermination(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				if (Config.DEBUG) {
					Log.e(TAG, "shutdown failed: ", e);
				}
			}
		}
		activity.unregisterReceiver(broadcastReceiver);
	}

	/**
	 * Enables the NFC so that messages can be exchanged. Attention: the
	 * enable() method must be called first!
	 */
	public void startInitiating() {
		transceiver.startInitiating();
	}
	
	/**
	 * Soft disables the NFC to prevent devices such as the Samsung Galaxy Note
	 * 3 (other devices may show the same behavior!) to restart the protocol
	 * after having send the last message!
	 * 
	 * This should be called after a successful communication. Once you want to
	 * restart the NFC capability, call enableNFC.
	 */
	public void stopInitiating() {
		transceiver.stopInitiating();
	}
	
	private boolean validateSequence(final NfcMessage request, final NfcMessage response) {
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
