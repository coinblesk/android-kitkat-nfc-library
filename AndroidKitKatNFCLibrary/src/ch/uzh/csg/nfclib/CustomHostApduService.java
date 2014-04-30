package ch.uzh.csg.nfclib;

import java.util.ArrayList;
import java.util.Arrays;

import android.app.Activity;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;
import ch.uzh.csg.nfclib.messages.NfcMessage;
import ch.uzh.csg.nfclib.transceiver.NfcTransceiver;
import ch.uzh.csg.nfclib.util.Config;
import ch.uzh.csg.nfclib.util.Constants;
import ch.uzh.csg.nfclib.util.NfcMessageReassembler;
import ch.uzh.csg.nfclib.util.NfcMessageSplitter;

//TODO: javadoc
public class CustomHostApduService extends HostApduService {
	
	public static final String TAG = "CustomHostApduService";
	
	/*
	 * NXP chip supports max 255 bytes (10 bytes is header of nfc protocol)
	 */
	protected static final int MAX_WRITE_LENGTH = 245;
	
	private static Activity hostActivity;
	private static NfcEventHandler eventHandler;
	private static IMessageHandler messageHandler;
	
	private static NfcMessageSplitter messageSplitter;
	private static NfcMessageReassembler messageReassembler;
	
	private static ArrayList<NfcMessage> fragments;
	private static int index = 0;
	
	private static long userIdReceived = 0;
	
	private static long timeDeactivated = 0;
	
	private static int lastSqNrReceived;
	private static int lastSqNrSent;
	private static NfcMessage lastMessage;
	private static int nofRetransmissions = 0;
	
	public static void init(Activity activity, NfcEventHandler eventHandler, IMessageHandler messageHandler) {
		hostActivity = activity;
		CustomHostApduService.eventHandler = eventHandler;
		CustomHostApduService.messageHandler = messageHandler;
		messageSplitter = new NfcMessageSplitter(MAX_WRITE_LENGTH);
		messageReassembler = new NfcMessageReassembler();
		
		fragments = null;
		index = 0;
		userIdReceived = 0;
		timeDeactivated = 0;
		
		lastSqNrReceived = 0;
		lastSqNrSent = 0;
		lastMessage = null;
		nofRetransmissions = 0;
	}
	
	/*
	 * The empty constructor is needed by android to instantiate the service.
	 * That is the reason why most fields are static.
	 */
	public CustomHostApduService() {
		if (hostActivity == null) {
			Log.d(TAG, "activity has not been set yet or user is not in the given activity");
		}
	}

	@Override
	public byte[] processCommandApdu(byte[] bytes, Bundle extras) {
		if (hostActivity == null) {
			Log.e(TAG, "The user is not in the correct activity but tries to establish a NFC connection.");
			return new NfcMessage(NfcMessage.ERROR, (byte) (0x00), null).getData();
		}
		
		if (selectAidApdu(bytes)) {
			/*
			 * The size of the returned message is specified in NfcTransceiver
			 * and is set to 2 actually.
			 */
			Log.d(TAG, "AID selected");
			
			long now = System.currentTimeMillis();
			long newUserId = CommandApdu.getUserId(bytes);
			
			if (newUserId == userIdReceived && (now - timeDeactivated < Config.SESSION_RESUME_THRESHOLD)) {
				return new NfcMessage(NfcMessage.AID_SELECTED, (byte) 0x00, null).getData();
			} else {
				userIdReceived = newUserId;
				eventHandler.handleMessage(NfcEvent.INITIALIZED, Long.valueOf(userIdReceived));
				resetStates();
				return new NfcMessage((byte) (NfcMessage.AID_SELECTED | NfcMessage.START_PROTOCOL), (byte) 0x00, null).getData();
			}
		} else if (readBinary(bytes)) {
			//TODO: check if this ok
			return new byte[] { 0x00 };
		}
		
		NfcMessage response = getResponse(bytes);
		if (response == null) {
			return null;
		} else {
			lastMessage = response;
			return response.getData();
		}
	}

	private boolean readBinary(byte[] bytes) {
		/*
		 * Based on the reported issue in
		 * https://code.google.com/p/android/issues/detail?id=58773, there is a
		 * failure in the Android NFC protocol. The IsoDep might transceive a
		 * READ BINARY, if the communication with the tag (or HCE) has been idle
		 * for a given time (125ms as mentioned on the issue report). This idle
		 * time can be changed with the EXTRA_READER_PRESENCE_CHECK_DELAY
		 * option.
		 */
		return Arrays.equals(bytes, Constants.READ_BINARY);
	}

	private boolean selectAidApdu(byte[] bytes) {
		if (bytes == null || bytes.length < 2)
			return false;
		else
			return bytes[0] == Constants.CLA_INS_P1_P2[0] && bytes[1] == Constants.CLA_INS_P1_P2[1];
	}

	private void resetStates() {
		messageReassembler.clear();
		fragments = null;
		index = 0;
		
		lastSqNrReceived = 0;
		lastSqNrSent = 0;
		lastMessage = null;
		
		nofRetransmissions = 0;
	}

	private NfcMessage getResponse(byte[] bytes) {
		NfcMessage incoming = new NfcMessage(bytes);
		Log.d(TAG, "received msg: "+incoming);
		
		byte status = (byte) (incoming.getStatus());
		
		if (status == NfcMessage.ERROR) {
			Log.d(TAG, "nfc error reported - returning null");
			eventHandler.handleMessage(NfcEvent.ERROR_REPORTED, NfcTransceiver.UNEXPECTED_ERROR);
			return null;
		}
		
		//TODO: pay attention to 255+1 --> should result in 1, not 0!!
		//TODO: pay attention to 255+1 --> should result in 1, not 0!! as well in NfcTransceiver!!!
		lastSqNrSent++;
		
		if (corruptMessage(bytes)) {
			return returnRetransmissionOrError();
		} else if (invalidSequenceNumber(incoming.getSequenceNumber())) {
			Log.d(TAG, "requesting retransmission because answer was not as expected");
			
			if (incoming.requestsRetransmission()) {
				//this is a deadlock, since both parties are requesting a retransmit
				eventHandler.handleMessage(NfcEvent.COMMUNICATION_ERROR, NfcTransceiver.UNEXPECTED_ERROR);
				return new NfcMessage(NfcMessage.ERROR, (byte) lastSqNrSent, null);
			}
			
			return returnRetransmissionOrError();
		} else if (incoming.requestsRetransmission()) {
			lastSqNrReceived++;
			if (nofRetransmissions < Config.MAX_RETRANSMITS) {
				nofRetransmissions++;
				// decrement, since it should have the same sq nr, but was
				// incremented above
				lastSqNrSent--;
				return lastMessage;
			} else {
				//Requesting retransmit failed
				eventHandler.handleMessage(NfcEvent.COMMUNICATION_ERROR, NfcTransceiver.UNEXPECTED_ERROR);
				return new NfcMessage(NfcMessage.ERROR, (byte) lastSqNrSent, null);
			}
		} else {
			nofRetransmissions = 0;
		}
		
		lastSqNrReceived++;
		
		NfcMessage toReturn;
		
		switch (status) {
		case NfcMessage.HAS_MORE_FRAGMENTS:
			Log.d(TAG, "has more fragments");
			messageReassembler.handleReassembly(incoming);
			return new NfcMessage(NfcMessage.GET_NEXT_FRAGMENT, (byte) lastSqNrSent, null);
		case NfcMessage.DEFAULT:
			Log.d(TAG, "handle default");
			
			eventHandler.handleMessage(NfcEvent.MESSAGE_RECEIVED, null);
			
			messageReassembler.handleReassembly(incoming);
			//TODO: what if implementation takes to long?? polling?
			byte[] response = messageHandler.handleMessage(messageReassembler.getData());
			messageReassembler.clear();
		
			fragments = messageSplitter.getFragments(response);
			Log.d(TAG, "returning: " + response.length + " bytes, " + fragments.size() + " fragments");
			if (fragments.size() == 1) {
				toReturn = fragments.get(0);
				toReturn.setSequenceNumber((byte) lastSqNrSent);
				lastSqNrReceived = lastSqNrSent = 0;
				index = 0;
				fragments = null;
				eventHandler.handleMessage(NfcEvent.MESSAGE_RETURNED, null);
			} else {
				toReturn = fragments.get(index++);
				toReturn.setSequenceNumber((byte) lastSqNrSent);
			}
			return toReturn;
		case NfcMessage.GET_NEXT_FRAGMENT:
			if (fragments != null && !fragments.isEmpty() && index < fragments.size()) {
				toReturn = fragments.get(index++);
				toReturn.setSequenceNumber((byte) lastSqNrSent);
				if (toReturn.getStatus() != NfcMessage.HAS_MORE_FRAGMENTS) {
					lastSqNrReceived = lastSqNrSent = 0;
					index = 0;
					fragments = null;
					eventHandler.handleMessage(NfcEvent.MESSAGE_RETURNED, null);
				}
				
				Log.d(TAG, "returning next fragment (index: "+(index-1)+")");
				return toReturn;
			} else {
				Log.e(TAG, "IsoDep wants next fragment, but there is nothing to reply!");
				eventHandler.handleMessage(NfcEvent.COMMUNICATION_ERROR, NfcTransceiver.UNEXPECTED_ERROR);
				return new NfcMessage(NfcMessage.ERROR, (byte) lastSqNrSent, null);
			}
		default:
				//TODO: handle, since this is an error!! should not receive something else than above
				//TODO: does this ever occur?
			
		}
		//TODO: fix this!
		return new NfcMessage(NfcMessage.DEFAULT, (byte) 0x00, null);
	}

	private NfcMessage returnRetransmissionOrError() {
		if (nofRetransmissions < Config.MAX_RETRANSMITS) {
			nofRetransmissions++;
			return new NfcMessage(NfcMessage.RETRANSMIT, (byte) lastSqNrSent, null);
		} else {
			//Requesting retransmit failed
			eventHandler.handleMessage(NfcEvent.COMMUNICATION_ERROR, NfcTransceiver.UNEXPECTED_ERROR);
			return new NfcMessage(NfcMessage.ERROR, (byte) lastSqNrSent, null);
		}
	}
	
	private boolean corruptMessage(byte[] bytes) {
		return bytes == null || bytes.length < NfcMessage.HEADER_LENGTH;
	}
	
	//TODO: code duplicated from NfcTransceiver! move to NfcMessage
	private boolean invalidSequenceNumber(byte sequenceNumber) {
		/*
		 * Because Java does not support unsigned bytes, we have to convert the
		 * (signed) byte to an integer in order to get values from 0 to 255
		 * (instead of -128 to 127)
		 */
		int temp = sequenceNumber & 0xFF;
		if (temp == 255) {
			if (lastSqNrReceived == 254)
				return false;
			else
				return true;
		}
		
		if (lastSqNrReceived == 255)
			lastSqNrReceived = 0;
		
		return temp != (lastSqNrReceived+1);
	}

	@Override
	public void onDeactivated(int reason) {
		Log.d(TAG, "deactivated due to " + (reason == HostApduService.DEACTIVATION_LINK_LOSS ? "link loss" : "deselected") + "("+reason+")");
		timeDeactivated = System.currentTimeMillis();
		eventHandler.handleMessage(NfcEvent.CONNECTION_LOST, null);
	}

}
