package ch.uzh.csg.nfclib;

import java.util.Random;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import ch.uzh.csg.comm.Utils;

public class TestUtils {
	
	public static byte[] getRandomBytes(int size) {
		byte[] bytes = new byte[size];
		new Random().nextBytes(bytes);
		return bytes;
	}
	
	@Test
	public void testUUID () {
		long m = 4231231516547346435l;
		long l = 1214556234334562546l;
		UUID u = new UUID(m, l);
		byte[] arr = Utils.uuidToByteArray(u);
		UUID u2 = Utils.byteArrayToUUID(arr, 0);
		Assert.assertEquals(m, u2.getMostSignificantBits());
		Assert.assertEquals(l, u2.getLeastSignificantBits());
		
	}
	

}
