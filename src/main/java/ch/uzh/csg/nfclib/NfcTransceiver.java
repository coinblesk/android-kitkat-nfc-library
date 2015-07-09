package ch.uzh.csg.nfclib;

import java.io.IOException;

/**
 * The implementation of this interface must handle the initialization and the
 * near field communication.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public interface NfcTransceiver {

	public static final String NFCTRANSCEIVER_NOT_ENABLED = "Could not write message, NfcTransceiver is not in initiating mode.";
	public static final String NFCTRANSCEIVER_NOT_CONNECTED = "Could not write message, NfcTransceiver is not connected.";
	public static final String UNEXPECTED_ERROR = "An unexpected error occured while writing over NFC.";

	

	

	/**
	 * Writes a {@link NfcMessage} to the NFC partner.
	 * 
	 * @param input
	 *            the {@link NfcMessage} to be send
	 * @return the response as {@link NfcMessage}
	 * @throws IOException
	 *             if there is an I/O error
	 */
	public byte[] write(byte[] input) throws IOException, NfcLibException;

	/**
	 * Returns the maximum transceive (send/receive) length.
	 */
	public int maxLen();

	public void close();
}
