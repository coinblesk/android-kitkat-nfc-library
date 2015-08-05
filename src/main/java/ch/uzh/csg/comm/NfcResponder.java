package ch.uzh.csg.comm;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;

import android.nfc.cardemulation.HostApduService;
import android.util.Log;
import ch.uzh.csg.comm.NfcMessage.Type;
import ch.uzh.csg.nfclib.HostApduServiceNfcLib;
import ch.uzh.csg.nfclib.NfcSetup;

/**
 * This class represents the counterpart of the {@link NfcSetup}. It listens
 * for incoming NFC messages and provides the appropriate response.
 * 
 * Message fragmentation and reassembly is handled internally.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class NfcResponder {
	private static final String TAG = "ch.uzh.csg.nfclib.NfcResponder";

	private final NfcResponseHandler responseHandler;
	private final int maxTransceiveLength;

	private final NfcMessageSplitter messageSplitter = new NfcMessageSplitter();
	private final Deque<NfcMessage> messageQueue = new LinkedList<NfcMessage>();
	private final Object lock = new Object();

	// state
	private NfcMessage lastMessageSent;
	private NfcMessage lastMessageReceived;
	private NfcMessage lateMessage = null;


	/**
	 * Instantiates a new object to response to incoming NFC messages.
	 * @param responseHandler2 
	 * 
	 * @param eventHandler
	 *            the {@link NfcInitiatorHandler} to listen for {@link NfcEvent}s
	 * @param messageHandler
	 *            the {@link TransceiveHandler} which provides appropriate
	 *            responses for incoming messages
	 */
	public NfcResponder(NfcResponseHandler responseHandler, int maxTransceiveLength) {
		this.responseHandler = responseHandler;
		this.maxTransceiveLength = maxTransceiveLength;
		messageSplitter.maxTransceiveLength(maxTransceiveLength);
		
		lastMessageSent = null;
		lastMessageReceived = null;	
	}
	
	public byte[] processIncomingData(byte[] bytes) {
		if (Config.DEBUG) {
			Log.d(TAG, "processCommandApdu with " + Arrays.toString(bytes));
		}
		
		NfcMessage inputMessage = new NfcMessage(bytes);
		
		
		byte[] intVal;
		int maxLen;
		switch(inputMessage.type()) {
		case READ_BINARY:
			if (Config.DEBUG) {
				Log.d(TAG, "keep alive message");
			}
			// no sequence number in here
			return new NfcMessage(Type.READ_BINARY).bytes();
		case AID_1:
			if (Config.DEBUG) {
				Log.d(TAG, "AID1 selected");
			}
			maxLen = Math.min(Integer.MAX_VALUE, maxTransceiveLength);
			messageSplitter.maxTransceiveLength(maxLen);
			intVal = Utils.intToByteArray(maxLen);
			return new NfcMessage(Type.SINGLE).payload(intVal).bytes();
		case AID_2:
			if (Config.DEBUG) {
				Log.d(TAG, "AID2 selected");
			}
			maxLen = Math.min(53, maxTransceiveLength);
			messageSplitter.maxTransceiveLength(maxLen);
			intVal = Utils.intToByteArray(maxLen);
			return new NfcMessage(Type.SINGLE).payload(intVal).bytes();
		case AID_3:
			if (Config.DEBUG) {
				Log.d(TAG, "AID3 selected");
			}
			maxLen = Math.min(245, maxTransceiveLength);
			messageSplitter.maxTransceiveLength(maxLen);
			intVal = Utils.intToByteArray(maxLen);
			return new NfcMessage(Type.SINGLE).payload(intVal).bytes();
		default:
			if (Config.DEBUG) {
				Log.d(TAG, "process regular message " + inputMessage);
			}
			
			final boolean check = inputMessage.check(lastMessageReceived);
			final boolean repeat = inputMessage.repeatLast(lastMessageReceived);
			
			
			NfcMessage outputMessage = null;
			if (!check && !repeat) {
				if (Config.DEBUG) {
					Log.e(TAG, "sequence number mismatch " + inputMessage.sequenceNumber() + 
							" / " + (lastMessageReceived == null ? -1 : lastMessageReceived.sequenceNumber()));
				}
				
				responseHandler.handleFailed(NfcSetup.UNEXPECTED_ERROR);
				outputMessage = new NfcMessage(Type.ERROR);
				return prepareWrite(outputMessage);
			}
			if (!check && repeat) {
				lastMessageReceived = inputMessage;
				return lastMessageSent.bytes();
			}
			try {
				outputMessage = handleRequest(inputMessage);
				lastMessageReceived = inputMessage;
				return prepareWrite(outputMessage);
			} catch (Exception e){
			    
				reset();
				responseHandler.handleFailed(e.toString());
				return new NfcMessage(Type.ERROR).bytes();
			}
			
		}
		
	}
	
	private void reset() {
		lastMessageSent = null;
		lastMessageReceived = null;
		lateMessage = null;
		messageSplitter.clear();
		messageQueue.clear();
	}
	
	private byte[] prepareWrite(NfcMessage outputMessage) {
		lastMessageSent = outputMessage.sequenceNumber(lastMessageSent);
		
		byte[] retVal = outputMessage.bytes();
		
		if (Config.DEBUG) {
			Log.d(TAG, "sending: " + outputMessage);
		}
		
		return retVal;
	}

	private NfcMessage checkForData() {
		final NfcMessage nfcMessage;
		synchronized (lock) {
			nfcMessage = lateMessage;
			lateMessage = null;
			return nfcMessage;
		}
	}

	private NfcMessage handleRequest(final NfcMessage incoming) throws Exception {
		if (Config.DEBUG) {
			Log.d(TAG, "received: " + incoming);
		}

		if (incoming.isError()) {
			if (Config.DEBUG) {
				Log.d(TAG, "nfc error reported - returning null");
			}
			reset();
			responseHandler.handleFailed(NfcSetup.UNEXPECTED_ERROR);
			return null;
		}

		switch (incoming.type()) {
		
		case SINGLE:
			NfcMessage msg = response(incoming.payload());
			if(!responseHandler.expectMoreMessages()) {
				reset();
			}
			return msg;
		case FRAGMENT:
		case FRAGMENT_LAST:
			if(incoming.payload().length > 0) {
				switch (incoming.type()) {
					case FRAGMENT:
						messageSplitter.reassemble(incoming);
						return new NfcMessage(Type.FRAGMENT);
					case FRAGMENT_LAST:
						messageSplitter.reassemble(incoming);
						final byte[] receivedData = messageSplitter.data();
						messageSplitter.clear();
						return response(receivedData);
					default:
						reset();
						responseHandler.handleFailed("unexpected type");
						return new NfcMessage(Type.ERROR);
				}
			} else if (incoming.type() == Type.FRAGMENT){
				//continue with our message queue
				if (messageQueue.isEmpty()) {
					if (Config.DEBUG) {
						Log.e(TAG, "nothing to return (get next fragment)");
					}
					reset();
					responseHandler.handleFailed(NfcSetup.UNEXPECTED_ERROR);
					return new NfcMessage(Type.ERROR);
				}
				msg = messageQueue.poll();
				if(msg.type() == Type.FRAGMENT_LAST) {
					if(!responseHandler.expectMoreMessages()) {
						reset();
					}
				}
				return msg;
			} else {
				if (Config.DEBUG) {
					Log.e(TAG, "unknown fragment: " + incoming.type()+ " for incoming msg: " + incoming);
				}
				reset();
				responseHandler.handleFailed("unknown fragment: " + incoming.type()+ " for incoming msg: " + incoming);
				return new NfcMessage(Type.ERROR);
			}
		case POLLING_RESPONSE:
			msg = checkForData();
			if (msg != null) {
				return msg;
			} else {
				return new NfcMessage(Type.POLLING_REQUEST);
			}
		case POLLING_REQUEST:
			return new NfcMessage(Type.POLLING_RESPONSE);
		case ERROR:
			reset();
			return new NfcMessage(Type.ERROR);
		default:
			if (Config.DEBUG) {
				Log.e(TAG, "unknown type: " + incoming.type()+ " for incoming msg: " + incoming);
			}
			reset();
			responseHandler.handleFailed("unknown type: " + incoming.type()+ " for incoming msg: " + incoming);
			return new NfcMessage(Type.ERROR);
		}
	}

	private NfcMessage response(final byte[] payload) throws Exception {
		final byte[] response = responseHandler.handleMessageReceived(payload, new ResponseLater(){
			@Override
			public void response(byte[] data) {
				synchronized (lock) {
					lateMessage = fragmentData(data);
				}
			}});
		
		// the user can decide to use sendLater. In that case, we'll start
		// to poll. This is triggered by returning null.
		if (response == null) {
			return new NfcMessage(NfcMessage.Type.POLLING_REQUEST);
		} else {
			return fragmentData(response);
		}
	}

	private NfcMessage fragmentData(byte[] response) {
		if (response == null) {
			return null;
		}
		for (NfcMessage msg : messageSplitter.getFragments(response)) {
			messageQueue.offer(msg);
		}

		if (Config.DEBUG) {
			Log.d(TAG, "returning: " + response.length + " bytes, " + messageQueue.size() + " fragments");
		}
		
		if (messageQueue.isEmpty()) {
			if (Config.DEBUG) {
				Log.e(TAG, "nothing to return - message queue is empty");
			}
			reset();
			responseHandler.handleFailed("nothing to return - message queue is empty");
			return new NfcMessage(Type.ERROR);
		}
		return messageQueue.poll();
	}

	/**
	 * This has to be called whenever the system detects that the NFC has been
	 * aborted.
	 * 
	 * @param reason
	 *            see {@link HostApduServiceNfcLib}
	 */
	public void onDeactivated(int reason) {
		if (Config.DEBUG) {
			Log.d(TAG, "deactivated due to " + 
					(reason == HostApduService.DEACTIVATION_LINK_LOSS ? "link loss" : "deselected") + "(" + reason + ")");
		}
		responseHandler.handleFailed(NfcSetup.TIMEOUT);
	}	
}
