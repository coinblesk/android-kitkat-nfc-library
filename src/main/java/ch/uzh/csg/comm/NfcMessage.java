package ch.uzh.csg.comm;

import java.util.Arrays;

/**
 * This is the NFC layer protocol message. It is responsible for sending
 * Messages between two devices over NFC. It is build to allow message
 * fragmentation and reassembly, based on the communication device's NFC message
 * size capabilities.
 * 
 * The dataflow is as follows:
 * 
 * header:
 * send AID			->
 * 					<- AID_RESPONSE
 * handshake completed
 * 
 * polling (state is kept on both sides):
 * send POLLING 	->
 * 					<- POLLING
 * 
 * request1:
 * send SINGLE		-> got message / no more messages expected
 * 
 * send FIRST		-> 
 *               	<- FRAGMENT/0
 * send FRAGMENT    ->
 * 					<- FRAGMENT/0
 * send FRAGEMNT_L	-> got message
 * ...
 * followup request1:
 * send FRAGMENT	-> 
 *               	<- FRAGMENT/0
 * send FRAGMENT    ->
 * 					<- FRAGMENT/0
 * send LAST		-> got message / no more messages expected
 * 
 * request2:
 * send FIRST		-> 
 *               	<- FRAGMENT/0
 * send FRAGMENT    ->
 * 					<- FRAGMENT/0
 * send LAST		-> got message / no more messages expected
 * 
 * reply1:
 * 					<- SINGLE
 * got message
 * 
 * reply2:
 * 					<- FRAGMENT
 * send FRAGMENT/0	->
 * 					<- FRAGMENT
 * send FRAGMENT/0	->
 * 					<- FRAGEMNT_L
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public class NfcMessage {
	
	/*
	 * When a remote NFC device wants to talk to your service, it sends a
	 * so-called "SELECT AID" APDU as defined in the ISO/IEC 7816-4
	 * specification.
	 */
	public static final byte[] CLA_INS_P1_P2 = { 0x00, (byte) 0xA4, 0x04, 0x00 };
	public static final byte[] AID_COINBLESK_1 = { (byte) 0xF0, (byte) 0xF0, 0x07, 0x77, (byte) 0xFF, 0x55, 0x0 };
	public static final byte[] AID_COINBLESK_2 = { (byte) 0xF0, (byte) 0xF0, 0x07, 0x77, (byte) 0xFF, 0x55, 0x36 };
	public static final byte[] AID_COINBLESK_3 = { (byte) 0xF0, (byte) 0xF0, 0x07, 0x77, (byte) 0xFF, 0x55, (byte) 0xF5 };
	public static final byte[] BTLE_INIT = { (byte) 0xF0, (byte) 0xF0, 0x07, 0x77, (byte) 0xFF, 0x55, (byte) 0xF5 };
	public static final byte[] CLA_INS_P1_P2_COINBLESK_1;
	public static final byte[] CLA_INS_P1_P2_COINBLESK_2;
	public static final byte[] CLA_INS_P1_P2_COINBLESK_3;
	static {
		// for details see:
		// http://www.cardwerk.com/smartcards/smartcard_standard_ISO7816-4_9_application-independent_card_services.aspx
		final byte Lc1 = (byte) AID_COINBLESK_1.length;
		final byte Lc2 = (byte) AID_COINBLESK_2.length;
		final byte Lc3 = (byte) AID_COINBLESK_3.length;
		// we return 1 + 4 bytes
		final byte Le = 5;
		CLA_INS_P1_P2_COINBLESK_1 = new byte[] { CLA_INS_P1_P2[0], CLA_INS_P1_P2[1], 
				CLA_INS_P1_P2[2], CLA_INS_P1_P2[3], Lc1, AID_COINBLESK_1[0], AID_COINBLESK_1[1], 
				AID_COINBLESK_1[2], AID_COINBLESK_1[3], AID_COINBLESK_1[4], AID_COINBLESK_1[5], 
				AID_COINBLESK_1[6], Le };
		
		CLA_INS_P1_P2_COINBLESK_2 = new byte[] { CLA_INS_P1_P2[0], CLA_INS_P1_P2[1], 
				CLA_INS_P1_P2[2], CLA_INS_P1_P2[3], Lc2, AID_COINBLESK_2[0], AID_COINBLESK_2[1], 
				AID_COINBLESK_2[2], AID_COINBLESK_2[3], AID_COINBLESK_2[4], AID_COINBLESK_2[5], 
				AID_COINBLESK_2[6], Le };
		
		CLA_INS_P1_P2_COINBLESK_3 = new byte[] { CLA_INS_P1_P2[0], CLA_INS_P1_P2[1], 
				CLA_INS_P1_P2[2], CLA_INS_P1_P2[3], Lc3, AID_COINBLESK_3[0], AID_COINBLESK_3[1], 
				AID_COINBLESK_3[2], AID_COINBLESK_3[3], AID_COINBLESK_3[4], AID_COINBLESK_3[5], 
				AID_COINBLESK_3[6], Le };
	}

	public static final byte[] READ_BINARY = { 0x00, (byte) 0xB0, 0x00, 0x00, 0x01 };

	public static final int HEADER_LENGTH = 1;

	// messages, uses the last 3 bits (bit 0-2), READ_BINARY, AID_1, AID_2, AID_3, NO_COINBLESK_MSG is never sent over the wire
	public enum Type {
		FRAGMENT, FRAGMENT_LAST, POLLING_REQUEST, SINGLE, ERROR, POLLING_RESPONSE, RESET, UNUSED2, READ_BINARY, AID_1, AID_2, AID_3;
	}
	
	// data
	private int type;
	private int sequenceNumber = 0;
	private byte[] payload = new byte[0];

	/**
	 * Sets the data of this message and returns it.
	 * 
	 * @param input
	 *            the header as well as the payload of this {@link NfcMessage}
	 */
	public NfcMessage(final byte[] input) {
		final int len = input.length;
		if (Arrays.equals(input, READ_BINARY)) {
			/*
			 * Based on the reported issue in
			 * https://code.google.com/p/android/issues/detail?id=58773, there
			 * is a failure in the Android NFC protocol. The IsoDep might
			 * transceive a READ BINARY, if the communication with the tag (or
			 * HCE) has been idle for a given time (125ms as mentioned on the
			 * issue report). This idle time can be changed with the
			 * EXTRA_READER_PRESENCE_CHECK_DELAY option.
			 */
			type = Type.READ_BINARY.ordinal();
		} else if (Arrays.equals(input, CLA_INS_P1_P2_COINBLESK_1)) {
			// we got the initial handshake
			type = Type.AID_1.ordinal();
		} else if (Arrays.equals(input, CLA_INS_P1_P2_COINBLESK_2)) {
			// we got the initial handshake
			type = Type.AID_2.ordinal();
		} else if (Arrays.equals(input, CLA_INS_P1_P2_COINBLESK_3)) {
			// we got the initial handshake
			type = Type.AID_3.ordinal();
		}
		else {
			// this is now a custom message
			//bit 0-3 are the types
			type = input[0] & 0x07;
			//bit 3-8 is are part of the sequence number
			sequenceNumber = (input[0] & 0xFF) >>> 3;

			if (len > HEADER_LENGTH) {
				final int payloadLen = len - HEADER_LENGTH;
				payload = new byte[payloadLen];
				System.arraycopy(input, HEADER_LENGTH, payload, 0, payloadLen);
			}
		}
	}

	/**
	 * Sets the type of this message and returns it.
	 * 
	 * @param messageType
	 *            the {@link Type} to set
	 */
	public NfcMessage(Type messageType) {
		type = messageType.ordinal();
	}

	/**
	 * Returns the type of this message.
	 */
	public Type type() {
		// type is encoded in the last 3 bits
		return Type.values()[type];
	}
	
	/**
	 * Sets the payload of this message and returns it.
	 * 
	 * @param payload
	 *            the payload to set
	 */
	public NfcMessage payload(final byte[] payload) {
		this.payload = payload;
		return this;
	}

	/**
	 * Returns the payload of this message.
	 */
	public byte[] payload() {
		return payload;
	}

	/**
	 * Sets the sequence number of this message and returns it.
	 * 
	 * @param previousMessage
	 *            the previous {@link NfcMessage} which has been sent over NFC
	 * @return this message with the appropriate sequence number
	 */
	public NfcMessage sequenceNumber(final NfcMessage previousMessage) {
		if (previousMessage == null) {
			sequenceNumber = 0;
		} else {
			sequenceNumber = (previousMessage.sequenceNumber + 1) % 32;
		}
		return this;
	}

	/**
	 * Returns the sequence number of this message.
	 */
	public int sequenceNumber() {
		return sequenceNumber;
	}
	
	/**
	 * Returns the sequence number of this message.
	 */
	public NfcMessage sequenceNumber(int sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
		return this;
	}

	/**
	 * Verifies that this message has a correct sequence number based on the
	 * previous {@link NfcMessage} sent or received.
	 * 
	 * @param previousMessage
	 *            the previous {@link NfcMessage} sent or received
	 * @return true if the sequence number is by one larger than the previous,
	 *         false otherwise
	 */
	public boolean check(final NfcMessage previousMessage) {
		final int check;
		if (previousMessage == null) {
			check = -1;
		} else {
			check = previousMessage.sequenceNumber;
		}
		return sequenceNumber == (check + 1) % 32;
	}
	
	/**
	 * Returns true, if the sequence number of this message is equals to the
	 * sequence number of the previous message. If the previous message is null,
	 * false is returned.
	 * 
	 * @param previousMessage
	 *            the last {@link NfcMessage} sent or received
	 */
	public boolean repeatLast(final NfcMessage previousMessage) {
		if (previousMessage == null) {
			return false;
		}
		return sequenceNumber == previousMessage.sequenceNumber;
	}

	/**
	 * Returns true if the type of this message is error.
	 */
	public boolean isError() {
		return type() == Type.ERROR;
	}

	/**
	 * Returns true if the type of this message is read binary.
	 */
	public boolean isReadBinary() {
		return type() == Type.READ_BINARY;
	}
	
	/**
	 * Returns true if the type of this message is aid selected, no limit
	 */
	public boolean isSelectAidApdu1() {
		return type() == Type.AID_1;
	}
	
	/**
	 * Returns true if the type of this message is aid selected, 54 byte limit
	 */
	public boolean isSelectAidApdu2() {
		return type() == Type.AID_2;
	}
	
	/**
	 * Returns true if the type of this message is aid selected, 245 byte limit
	 */
	public boolean isSelectAidApdu3() {
		return type() == Type.AID_3;
	}
	
	/**
	 * Returns true if the type of this message is aid selected, 245 byte limit
	 */
	public boolean isFragment() {
		return type() == Type.FRAGMENT;
	}
	
	public boolean isGetNextFragment() {
		return type() == Type.FRAGMENT && payload.length == 0;
	}

	/**
	 * Returns the bytes of this message (i.e., serializes it).
	 */
	@SuppressWarnings("incomplete-switch")
	public byte[] bytes() {
		switch(type()) {
		case AID_1: //no limit
			return CLA_INS_P1_P2_COINBLESK_1;
		case AID_2: //54 byte limit
			return CLA_INS_P1_P2_COINBLESK_2;
		case AID_3: //245 byte limit
			return CLA_INS_P1_P2_COINBLESK_3;
		case READ_BINARY:
			return new byte[] { 0x00 };
		}

		final int len = payload.length;
		final byte[] output = new byte[HEADER_LENGTH + len];
		output[0] = (byte) ((type & 0x7) | ((sequenceNumber << 3) & 0xF8));
		System.arraycopy(payload, 0, output, HEADER_LENGTH, len);
		return output;
	}

	@Override
	public boolean equals(final Object o) {
		if (o == this) {
			return true;
		}
		if (!(o instanceof NfcMessage)) {
			return false;
		}
		final NfcMessage m = (NfcMessage) o;
		return m.type == type 
				&& (m.sequenceNumber % 32) == (sequenceNumber % 32) 
				&& Arrays.equals(m.payload, payload);
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("NfcMsg: ");
		sb.append("type: ").append(type().toString());
		sb.append("/").append(sequenceNumber);
		sb.append(",len:").append(payload.length);
		return sb.toString();
	}	
}
