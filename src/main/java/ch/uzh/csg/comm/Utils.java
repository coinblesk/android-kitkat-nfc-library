package ch.uzh.csg.comm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * This is a class for miscellaneous functions.
 * 
 * @author Jeton Memeti (initial version)
 * @author Thomas Bocek (simplification, refactoring)
 * 
 */
final public class Utils {

	/**
	 * Returns a long as a byte array.
	 */
	public static byte[] longToByteArray(final long value) {
		return new byte[] {
				(byte) (value >> 56),
				(byte) (value >> 48),
				(byte) (value >> 40),
				(byte) (value >> 32),
				(byte) (value >> 24),
				(byte) (value >> 16),
				(byte) (value >> 8),
				(byte) value
			};
	}

	/**
	 * Returns a byte array as long.
	 * 
	 * @param array
	 *            the byte array (including possibly also other data)
	 * @param offset
	 *            the index where to start reading the 8 bytes to convert to a
	 *            long
	 */
	public static long byteArrayToLong(final byte[] array, final int offset) {
		return ((long) (array[offset] & 0xff) << 56)
				| ((long) (array[offset + 1] & 0xff) << 48)
				| ((long) (array[offset + 2] & 0xff) << 40)
				| ((long) (array[offset + 3] & 0xff) << 32)
				| ((long) (array[offset + 4] & 0xff) << 24)
				| ((long) (array[offset + 5] & 0xff) << 16)
				| ((long) (array[offset + 6] & 0xff) << 8)
				| ((long) (array[offset + 7] & 0xff));
	}

	/**
	 * Returns an integer as a byte array.
	 */
	public static byte[] intToByteArray(final int value) {
		return new byte[] {
				(byte) (value >>> 24),
				(byte) (value >>> 16),
				(byte) (value >>> 8),
				(byte) value
			};
	}

	/**
	 * Returns a byte array as integer.
	 * 
	 * @param array
	 *            the byte array (including possibly also other data)
	 * @param offset
	 *            the index where to start reading the 4 bytes to convert to a
	 *            long
	 */
	public static int byteArrayToInt(final byte[] array, final int offset) {
		return (array[offset] & 0xff) << 24
				| (array[offset + 1] & 0xff) << 16
				| (array[offset + 2] & 0xff) << 8
				| (array[offset + 3] & 0xff);
	}

	/**
	 * Merges two byte arrays into one.
	 */
	public static byte[] merge(final byte[] array1, final byte[] array2) {
		final byte[] combined = new byte[array1.length + array2.length];
		System.arraycopy(array1, 0, combined, 0, array1.length);
		System.arraycopy(array2, 0, combined, array1.length, array2.length);
		return combined;
	}
	
	public static UUID hashToUUID(final byte[] data) {
	    try {
			final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
		    final byte[] hash = sha256.digest(data);
		    for(int i=0;i<16;i++) {
		    	hash[i]^= hash[i+16];
		    }
		    final long most = byteArrayToLong(hash, 0);
		    final long least = byteArrayToLong(hash, 8);
	    
		    return new UUID(most, least);
	    
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("sha-256 not present?", e);
		} 
	    
	}

	public static short byteArrayToShort(byte[] array, int offset) {
		return (short) ((array[offset] & 0xff) << 8 | (array[offset + 1] & 0xff));
	}

	public static byte[] shortToByteArray(short value) {
		return new byte[] {
				(byte) (value >>> 8),
				(byte) value
			};
	}
	
	public static byte[] uuidToByteArray(UUID uuid) {
		return new byte[] {
				(byte) (uuid.getLeastSignificantBits() >> 56),
				(byte) (uuid.getLeastSignificantBits() >> 48),
				(byte) (uuid.getLeastSignificantBits() >> 40),
				(byte) (uuid.getLeastSignificantBits() >> 32),
				(byte) (uuid.getLeastSignificantBits() >> 24),
				(byte) (uuid.getLeastSignificantBits() >> 16),
				(byte) (uuid.getLeastSignificantBits() >> 8),
				(byte) uuid.getLeastSignificantBits(),
				
				(byte) (uuid.getMostSignificantBits() >> 56),
				(byte) (uuid.getMostSignificantBits() >> 48),
				(byte) (uuid.getMostSignificantBits() >> 40),
				(byte) (uuid.getMostSignificantBits() >> 32),
				(byte) (uuid.getMostSignificantBits() >> 24),
				(byte) (uuid.getMostSignificantBits() >> 16),
				(byte) (uuid.getMostSignificantBits() >> 8),
				(byte) uuid.getMostSignificantBits()
			};
	}
	
	public static UUID byteArrayToUUID(byte[] array, int offset) {
		long leastSignificantBits = byteArrayToLong(array, 0);
		long mostSignificantBits = byteArrayToLong(array, 8);
		return new UUID(mostSignificantBits, leastSignificantBits);
	}
}
