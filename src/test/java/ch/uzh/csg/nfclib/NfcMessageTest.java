package ch.uzh.csg.nfclib;


import org.junit.Assert;
import org.junit.Test;

import ch.uzh.csg.nfclib.NfcMessage;
import ch.uzh.csg.nfclib.NfcMessage.Type;

public class NfcMessageTest {
	
	@Test
	public void testHeader1() {
		NfcMessage m1 = new NfcMessage(Type.DONE);
		m1.sequenceNumber(1024);
		m1.payload(new byte[100]);
		byte[] transfer = m1.bytes();
		NfcMessage m2 = new NfcMessage(transfer);
		Assert.assertEquals(m1, m2);
		Assert.assertEquals(Type.DONE, m2.type());
		Assert.assertEquals(1024, m2.sequenceNumber());
		Assert.assertEquals(100, m2.payload().length);
		Assert.assertFalse(m2.isResume());
	}
	
	@Test
	public void testHeader2() {
		NfcMessage m1 = new NfcMessage(Type.FRAGMENT);
		m1.sequenceNumber(2000);
		m1.payload(new byte[1000]);
		m1.resume();
		byte[] transfer = m1.bytes();
		NfcMessage m2 = new NfcMessage(transfer);
		Assert.assertEquals(m1, m2);
		Assert.assertEquals(Type.FRAGMENT, m2.type());
		Assert.assertEquals(2000, m2.sequenceNumber());
		Assert.assertEquals(1000, m2.payload().length);
		Assert.assertTrue(m2.isResume());
	}
	
	@Test
	public void testHeader3() {
		NfcMessage m1 = new NfcMessage(Type.ERROR);
		m1.sequenceNumber(5000);
		m1.payload(new byte[10000]);
		m1.resume();
		byte[] transfer = m1.bytes();
		NfcMessage m2 = new NfcMessage(transfer);
		Assert.assertEquals(m1, m2);
		Assert.assertEquals(Type.ERROR, m2.type());
		Assert.assertEquals(904, m2.sequenceNumber());
		Assert.assertEquals(10000, m2.payload().length);
		Assert.assertTrue(m2.isResume());
	}
	
	@Test
	public void testHeader4() {
		NfcMessage m1 = new NfcMessage(Type.AID_3);
		m1.sequenceNumber(5000);
		m1.payload(new byte[10000]);
		m1.resume();
		byte[] transfer = m1.bytes();
		Assert.assertEquals(0xF5, transfer[transfer.length-2] & 0xff);
	}
	
	@Test
	public void testHeader5() {
		byte[] intVal = Utils.intToByteArray(1);
		NfcMessage m1 = new NfcMessage(Type.AID_RESPONSE).payload(intVal);
		byte[] transfer = m1.bytes();
		byte[] expected = new byte[]{2,0,0,0,0,1};
		Assert.assertArrayEquals(expected, transfer);
	}
	
	

}
