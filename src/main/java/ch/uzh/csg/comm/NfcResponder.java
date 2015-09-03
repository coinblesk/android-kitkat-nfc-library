package ch.uzh.csg.comm;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.nfc.cardemulation.HostApduService;
import ch.uzh.csg.comm.NfcMessage.Type;
import ch.uzh.csg.nfclib.HostApduServiceNfcLib;
import ch.uzh.csg.nfclib.NfcInitiatorSetup;

/**
 * This class represents the counterpart of the {@link NfcInitiatorSetup}. It listens
 * for incoming NFC messages and provides the appropriate response.
 * 
 * Message fragmentation and reassembly is handled internally.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class NfcResponder {
	private static final Logger LOGGER = LoggerFactory.getLogger(NfcResponder.class);

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
	
	public NfcResponseHandler getResponseHandler() {
		return responseHandler;
	}
	
	public byte[] processIncomingData(byte[] input) {
		NfcMessage inputMessage = new NfcMessage(input);
		return processIncomingData(inputMessage).bytes();
	}
	
	public NfcMessage processIncomingData(NfcMessage inputMessage) {
		if (Config.DEBUG) {
			LOGGER.debug( "processCommandApdu with {}", Arrays.toString(inputMessage.payload()));
		}
		
		if(inputMessage.isFirst()) {
			LOGGER.debug( "first message, reset state");
			reset();
		}
		
		
		byte[] array;
		byte[] merged;
		int maxLen;
		switch(inputMessage.type()) {
		case READ_BINARY:
			if (Config.DEBUG) {
				LOGGER.debug( "keep alive message");
			}
			// no sequence number in here
			return new NfcMessage(Type.READ_BINARY);
		case AID_1:
			if (Config.DEBUG) {
				LOGGER.debug( "AID1 selected");
			}
			maxLen = Math.min(Short.MAX_VALUE, maxTransceiveLength);
			messageSplitter.maxTransceiveLength(maxLen);
			array = Utils.shortToByteArray((short)maxLen);
			merged = Utils.merge((byte) (messageQueue.isEmpty()? 0: 1), array, responseHandler.getUUID());
			return new NfcMessage(Type.SINGLE).payload(merged);
		case AID_2:
			if (Config.DEBUG) {
				LOGGER.debug( "AID2 selected");
			}
			maxLen = Math.min(53, maxTransceiveLength);
			messageSplitter.maxTransceiveLength(maxLen);
			array = Utils.shortToByteArray((short)maxLen);
			merged = Utils.merge((byte) (messageQueue.isEmpty()? 0: 1), array, responseHandler.getUUID());
			return new NfcMessage(Type.SINGLE).payload(merged);
		case AID_3:
			if (Config.DEBUG) {
				LOGGER.debug( "AID3 selected");
			}
			maxLen = Math.min(245, maxTransceiveLength);
			messageSplitter.maxTransceiveLength(maxLen);
			array = Utils.shortToByteArray((short)maxLen);
			merged = Utils.merge((byte) (messageQueue.isEmpty()? 0: 1), array, responseHandler.getUUID());
			return new NfcMessage(Type.SINGLE).payload(merged);
		default:
			if (Config.DEBUG) {
				LOGGER.debug( "process regular message {}", inputMessage);
			}
			
			final boolean check = inputMessage.check(lastMessageReceived);
			final boolean repeat = inputMessage.repeatLast(lastMessageReceived);
			
			NfcMessage outputMessage = null;
			if (!check && !repeat) {
				LOGGER.error( "sequence number mismatch {} / {}", inputMessage.sequenceNumber(), 
							(lastMessageReceived == null ? -1 : lastMessageReceived.sequenceNumber()));
				
				
				responseHandler.handleFailed(NfcInitiatorSetup.INVALID_SEQUENCE);
				outputMessage = new NfcMessage(Type.ERROR);
				NfcMessage msg = prepareWrite(outputMessage);
				reset();
				return msg;
			}
			if (!check && repeat && lastMessageSent!=null) {
				if (Config.DEBUG) {
					LOGGER.debug( "repeat last message {}", lastMessageSent);
				}
				lastMessageReceived = inputMessage;
				//check if we need to fragment - happens on handover from BT to NFC
				List<NfcMessage> list = messageSplitter.getFragments(lastMessageSent.payload());
				if(list.size() <= 1) {
					return lastMessageSent;
				} else {
					responseHandler.handleFailed("BT - NFC handover, need to reinitiate");
					outputMessage = new NfcMessage(Type.ERROR);
					NfcMessage msg = prepareWrite(outputMessage);
					reset();
					return msg;
				}
			}
			try {
				outputMessage = handleRequest(inputMessage);
				lastMessageReceived = inputMessage;
				NfcMessage msg = prepareWrite(outputMessage);
				if(msg.isError() || msg.isErrorReply()) {
					reset();
				}
				return msg;
			} catch (Exception e){
			    e.printStackTrace();
				responseHandler.handleFailed(e.toString());
				outputMessage = new NfcMessage(Type.ERROR);
				NfcMessage msg = prepareWrite(outputMessage);
				reset();
				return msg;
			}
			
		}
		
	}
	
	public void reset() {
		LOGGER.debug( "reset state");
		lastMessageSent = null;
		lastMessageReceived = null;
		lateMessage = null;
		messageSplitter.clear();
		messageQueue.clear();
	}
	
	private NfcMessage prepareWrite(NfcMessage outputMessage) {
		lastMessageSent = outputMessage.sequenceNumber(lastMessageSent);
		
		if (Config.DEBUG) {
			LOGGER.debug( "sending: {}", outputMessage);
		}
		
		return outputMessage;
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
			LOGGER.debug( "received: {}", incoming);
		}

		if (incoming.isError()) {
			if (Config.DEBUG) {
				LOGGER.debug( "nfc error reported - returning error response");
			}
			responseHandler.handleFailed(NfcInitiatorSetup.UNEXPECTED_ERROR);
			return new NfcMessage(Type.ERROR_REPLY);
		}

		switch (incoming.type()) {
		
		case SINGLE:
			NfcMessage msg = response(incoming.payload());
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
						responseHandler.handleFailed("unexpected type");
						return new NfcMessage(Type.ERROR);
				}
			} else if (incoming.type() == Type.FRAGMENT){
				//continue with our message queue
				if (messageQueue.isEmpty()) {
					LOGGER.error( "nothing to return (get next fragment)");
					reset();
					responseHandler.handleFailed(NfcInitiatorSetup.UNEXPECTED_ERROR);
					return new NfcMessage(Type.ERROR);
				}
				return messageQueue.poll();
			} else {
				LOGGER.error( "unknown fragment: {} for incoming msg: {}", incoming.type(), incoming);
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
			LOGGER.error( "unknown type: {} for incoming msg: {}", incoming.type(), incoming);
			reset();
			responseHandler.handleFailed("unknown type: " + incoming.type()+ " for incoming msg: " + incoming);
			return new NfcMessage(Type.ERROR);
		}
	}
	
	public ResponseLater lateResponder() {
		return new ResponseLater(){
			@Override
			public void response(byte[] data) {
				synchronized (lock) {
					lateMessage = fragmentData(data);
				}
			}};
	}

	private NfcMessage response(final byte[] payload) throws Exception {
		final byte[] response = responseHandler.handleMessageReceived(payload, lateResponder());
		
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
			LOGGER.debug( "returning: {} bytes, {} fragments",  response.length, messageQueue.size());
		}
		
		if (messageQueue.isEmpty()) {
			LOGGER.error( "nothing to return - message queue is empty");
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
			LOGGER.debug( "deactivated due to {} ({})", 
					(reason == HostApduService.DEACTIVATION_LINK_LOSS ? "link loss" : "deselected"), reason);
		}
		responseHandler.nfcTagLost();
	}

	public void setMtu(int mtu) {
		messageSplitter.maxTransceiveLength(mtu);
		
	}	
}
