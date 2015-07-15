package ch.uzh.csg.nfclib;

import java.security.KeyPair;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import ch.uzh.csg.PaymentMessage;

public class TestPaymentMessage {
	
	@Test
	public void testPaymentMessage1() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentRequest(kp.getPublic(), "hallo", new byte[6], 5, new byte[20]);
		byte[] transfer = pm1.toBytes(kp.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, null);
		Assert.assertEquals(pm1, pm2);
		Assert.assertTrue(pm2.isVerified());
	}
	
	@Test(expected = RuntimeException.class)
	public void testPaymentMessageLong() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentRequest(kp.getPublic(), 
				"halouoeauoeauoeauoauoeauaoeuoeauauoeauaoeuoeauauaoeuaoeuaoeuoeauoeauoeauaoeuoeauoeauoauaoeuoeauoeauaoeuoeauoeauoeauaoeuoeauaoeuaoeuoeauaoeuoeaoeauoeauaoeaoeauauaoeuoeaaoeuauaouauoeaoeaoeauaoeuaueoaeoeauaoeuoeauoeaaoaooauaouaouaoeueauoeauoealooeauaoeuaoeuuaoeuaoeuaoeuoaeuaouaoeuoaeu", new byte[6], 5, new byte[20]);
		pm1.toBytes(kp.getPrivate());
	}
	
	@Test
	public void testPaymentMessage2() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		Random rnd = new Random(1);
		byte[] bt = new byte[6];
		rnd.nextBytes(bt);
		byte[] ad = new byte[20];
		rnd.nextBytes(ad);
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentRequest(kp.getPublic(), "hallo", bt, 2143434245, ad);
		byte[] transfer = pm1.toBytes(kp.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, null);
		Assert.assertEquals(pm1, pm2);
		Assert.assertTrue(pm2.isVerified());
	}
	
	@Test
	public void testPaymentMessageFail() throws Exception {
		KeyPair kp1 = PaymentMessage.generateKeys();
		KeyPair kp2 = PaymentMessage.generateKeys();
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentRequest(kp1.getPublic(), "hallo", new byte[6], 5, new byte[20]);
		byte[] transfer = pm1.toBytes(kp2.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, null);
		Assert.assertEquals(pm1, pm2);
		Assert.assertFalse(pm2.isVerified());
	}
	
	@Test
	public void testPaymentMessageResponse() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		Random rnd = new Random(1);
		byte[] tx = new byte[2000];
		rnd.nextBytes(tx);
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentResponseOk(kp.getPublic(), "hallo", new byte[6], tx);
		byte[] transfer = pm1.toBytes(kp.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, null);
		Assert.assertEquals(pm1, pm2);
		Assert.assertTrue(pm2.isVerified());
	}
	
	@Test
	public void testPaymentMessageFromServer1() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentResponseNok();
		byte[] transfer = pm1.toBytes(kp.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, kp.getPublic());
		Assert.assertEquals(pm1, pm2);
		Assert.assertTrue(pm2.isVerified());
	}
	
	@Test
	public void testPaymentMessageFromServer2() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		KeyPair kp2 = PaymentMessage.generateKeys();
		PaymentMessage pm1 = PaymentMessage.contactAndPaymentResponseNok();
		byte[] transfer = pm1.toBytes(kp.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, kp2.getPublic());
		Assert.assertEquals(pm1, pm2);
		Assert.assertFalse(pm2.isVerified());
	}
	
	@Test
	public void testPaymentMessageFromServerOk() throws Exception {
		KeyPair kp = PaymentMessage.generateKeys();
		byte[] tx = new byte[5000];
		Random rnd = new Random(1);
		rnd.nextBytes(tx);
		PaymentMessage pm1 = PaymentMessage.fromServerRequestOk(tx);
		byte[] transfer = pm1.toBytes(kp.getPrivate());
		PaymentMessage pm2 = PaymentMessage.fromBytes(transfer, kp.getPublic());
		Assert.assertEquals(pm1, pm2);
		Assert.assertTrue(pm2.isVerified());
	}
}
