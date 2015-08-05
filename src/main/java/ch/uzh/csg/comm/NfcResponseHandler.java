package ch.uzh.csg.comm;

/**
 * The implementation of this interface must implement what has to be done on
 * the given {@link NfcEvent}.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
public interface NfcResponseHandler {
	
	/**
	 * Handles and takes appropriate steps on any given {@link NfcEvent}.
	 * Based on the type, further data may be provided in the object parameter.
	 * @param responseLater 
	 * 
	 * @param event
	 *            the given {@link NfcEvent}
	 * @param object
	 *            additional data or null
	 * @return 
	 */
	public abstract byte[] handleMessageReceived(byte[] message, ResponseLater responseLater) throws Exception;
	
	public boolean expectMoreMessages();
	
	public abstract void handleFailed(String message);
	
	/**
	 * Intermediate message during the NFC comunication
	 * @param message
	 */
	public void handleStatus(String message);

}
