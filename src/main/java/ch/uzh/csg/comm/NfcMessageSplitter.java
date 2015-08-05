package ch.uzh.csg.comm;

import java.util.ArrayList;
import java.util.Arrays;

import ch.uzh.csg.comm.NfcMessage.Type;


/**
 * This is responsible for byte array fragmentation into {@link NfcMessage}s in
 * order to not exceed the maximum allowed message length by the underlying NFC
 * technology.
 * 
 * This class also handles the reassembly of incoming {@link NfcMessage}s.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class NfcMessageSplitter {
	
	private int payloadLength = Integer.MAX_VALUE;
	private byte[] data = null;
	
	/**
	 * Returns a new NfcMessageSplitter to handle the fragmentation and
	 * reassembly of NfcMessages.
	 * 
	 * @param maxTransceiveLength
	 *            the maximum number of bytes which can be send at once by the
	 *            underlying NFC technology
	 */
	public NfcMessageSplitter maxTransceiveLength(int maxTransceiveLength) {
		payloadLength = maxTransceiveLength - NfcMessage.HEADER_LENGTH;
		return this;
	}
	
	/**
	 * Fragments the payload into a number of NfcMessages so that no NfcMessage
	 * exceeds the maxTransceiveLength. If no fragmentation is needed (because
	 * the payload does not reach the threshold), then a list containing only
	 * one NfcMessage is returned.
	 * 
	 * The sequence number of the NfcMessages is not set here (all messages in
	 * the list have the sequence number 0)! It must be set appropriately
	 * elsewhere.
	 * 
	 * @param payload
	 *            the whole message or byte array to be send by NFC
	 * @return an ArrayList of NfcMessages containing the fragmented payload
	 */
	public ArrayList<NfcMessage> getFragments(final byte[] payload) {
		final int len = payload.length;
		/*
		 * Returns the number of fragments the whole message needs to be split
		 * into (taking into account protocol headers etc.).
		 */
		final int fragments = (len + payloadLength - 1) / payloadLength;
		System.err.println("len: "+len+" payloadlen: "+payloadLength);
		final ArrayList<NfcMessage> list = new ArrayList<NfcMessage>(fragments);
		
		if(fragments == 1) {
			final NfcMessage nfcMessage = new NfcMessage(Type.SINGLE).payload(payload);
			list.add(nfcMessage);
			return list;
		}

		for (int i = 0; i < fragments; i++) {
			final int start = i * payloadLength;
			final boolean last = start + payloadLength >= payload.length;
			final int end = last ? payload.length : (start + payloadLength);

			final byte[] temp = Arrays.copyOfRange(payload, start, end);
			final Type type;
			if(last) {
				type = Type.FRAGMENT_LAST;
			} else {
				type = Type.FRAGMENT;
			}
			final NfcMessage nfcMessage = new NfcMessage(type).payload(temp);
			list.add(nfcMessage);
		}

		return list;
	}
	
	/**
	 * Handles an incoming NFC message. If this is not the first NFC message,
	 * the payload is appended to the temporal internal buffer.
	 * 
	 * @param nfcMessage
	 *            the incoming NFC message
	 */
	public void reassemble(final NfcMessage nfcMessage) {
		if (data == null || data.length == 0) {
			data = nfcMessage.payload();
		} else {
			final byte[] temp = new byte[data.length + nfcMessage.payload().length];
			System.arraycopy(data, 0, temp, 0, data.length);
			System.arraycopy(nfcMessage.payload(), 0, temp, data.length, nfcMessage.payload().length);
			data = temp;
		}
	}
	
	/**
	 * Clears the internal buffer.
	 */
	public void clear() {
		this.data = null;
	}
	
	/**
	 * Returns the buffer, which is the sum of the concatenated NFC messages.
	 */
	public byte[] data() {
		return data;
	}
	
}
