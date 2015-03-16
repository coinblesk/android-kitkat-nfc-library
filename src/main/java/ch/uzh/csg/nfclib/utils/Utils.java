package ch.uzh.csg.nfclib.utils;

/**
 * This is a class for miscellaneous functions.
 * 
 * @author Jeton Memeti
 * @author Thomas Bocek
 * 
 */
public class Utils {

	/**
	 * Returns a long as a byte array.
	 */
	public static byte[] longToByteArray(long value) {
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
	public static long byteArrayToLong(byte[] array, int offset) {
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
	public static byte[] intToByteArray(int value) {
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
	public static int byteArrayToInt(byte[] array, int offset) {
		return (array[offset] & 0xff) << 24
				| (array[offset + 1] & 0xff) << 16
				| (array[offset + 2] & 0xff) << 8
				| (array[offset + 3] & 0xff);
	}

	/**
	 * Merges two byte arrays into one.
	 */
	public static byte[] merge(byte[] array1, byte[] array2) {
		byte[] combined = new byte[array1.length + array2.length];
		System.arraycopy(array1, 0, combined, 0, array1.length);
		System.arraycopy(array2, 0, combined, array1.length, array2.length);
		return combined;
	}

}
