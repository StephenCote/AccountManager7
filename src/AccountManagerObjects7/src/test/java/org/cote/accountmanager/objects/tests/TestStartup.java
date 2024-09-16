package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.security.PublicKey;

import javax.crypto.Cipher;

import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.ParameterUtil;
import org.junit.Test;

public class TestStartup extends BaseTest {

	

	@Test
	public void TestFieldClone() {
		logger.info("Test Field Clone");
		CryptoBean bean = new CryptoBean();
		CryptoBean bean2 = new CryptoBean();
		CryptoBean bean3 = new CryptoBean();
		try {
			bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, "HmacSHA256");
			bean.set(FieldNames.FIELD_HASH_FIELD_ALGORITHM, "HmacSHA256");
			bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSIZE, 256);
			CryptoFactory.getInstance().setPassKey(bean, CryptoFactory.getInstance().randomKey(256), false);
			CryptoFactory.getInstance().setMembers(bean2, bean, false);
			
			Cipher encCipher = CryptoFactory.getInstance().getEncryptCipherKey(bean);
			assertNotNull("Cipher is null", encCipher);
			Cipher decCipher = CryptoFactory.getInstance().getDecryptCipherKey(bean);
			assertNotNull("Cipher is null", decCipher);
			String serial = CryptoFactory.getInstance().serialize(bean, false, false, true, true, true);
			CryptoFactory.getInstance().importCryptoBean(bean3, serial.getBytes(), false);
		} catch (NullPointerException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}

		//logger.info(bean2.toFullString());
		//logger.info(bean3.toFullString());
		//CryptoFactory.getInstance().generateSecretKey(bean);
	}

	@Test
	public void TestSystemStartup() {
		logger.info("Test System Startup");
		Factory mf = ioContext.getFactory();
		boolean error = false;
		try {
			BaseRecord org = mf.makeOrganization("/Development", OrganizationEnumType.DEVELOPMENT, 0L);
			BaseRecord testMember1 = mf.getCreateUser(orgContext.getAdminUser(), "testMember4", org.get(FieldNames.FIELD_ID));

			BaseRecord per1 = mf.getCreateDirectoryModel(testMember1, ModelNames.MODEL_PERSON, "Demo Person 1", "~/Persons", org.get(FieldNames.FIELD_ID));

			String token = TokenService.createJWTToken(testMember1);
			assertNotNull("Token is null", token);
			logger.info("Token: " + token);
			
			BaseRecord tok = mf.newInstance(ModelNames.MODEL_TOKEN, testMember1, null, ParameterUtil.parameters("""
					{
						FieldNames.FIELD_NAME: FieldNames.FIELD_NAME,
						"value": "Demo Token"
					},
					{
						FieldNames.FIELD_NAME: "expirySeconds",
						"value": 30
					}
			"""));

			
			BaseRecord dok = mf.template(ModelNames.MODEL_DATA, """
					{
						FieldNames.FIELD_NAME: "Demo Data",
						"contentType": "text/plain"
						
					}
			""");
			assertNotNull("Dok is null", dok);
			
			logger.info("Test: " + dok.get(FieldNames.FIELD_NAME) + " / " + dok.getFields().size());

			assertNotNull("Token is null", tok);
			
			String valTok = TokenService.validateTokenToSubject(token);
			assertNotNull("Token subject is null", valTok);
			
			logger.info("Token subject: " + valTok);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertFalse("An error was encountered", error);

	}

	/*
	@Test
	public void TestRelIndex() {
		Factory mf = ioContext.getFactory();
		BaseRecord testMember1 = mf.getCreateUser(orgContext.getAdminUser(), "testMember1", orgContext.getOrganizationId());
		BaseRecord per1 = mf.getCreateDirectoryModel(testMember1, ModelNames.MODEL_PERSON, "Demo Person 1", "~/Persons", orgContext.getOrganizationId());
		assertNotNull("Per1 is null", per1);
		/ *
		BaseRecord dir = ioContext.getPathUtil().makePath(testMember1, ModelNames.MODEL_GROUP, "~/Persons", "DATA", orgContext.getOrganizationId());
		BaseRecord per = ioContext.getRecordUtil().getRecord(testMember1, ModelNames.MODEL_PERSON, "Demo Person 1", 0L, (long)dir.get(FieldNames.FIELD_ID), 0L);
		assertNotNull("Per is null", per);
		* /
		
	}
	*/
	/*
	@Test
	public void TestRootIndex() {
		Factory mf = ioContext.getFactory();
		FileIndexer2 fix = ioContext.getIndexManager().getInstance(ModelNames.MODEL_GROUP);
		fix.setTrace(true);
		IndexEntry2[] ents = new IndexEntry2[0];
		try {
			ents =fix.findIndexEntries(-1L, -1L, -1L, orgContext.getOrganizationId(), null, null, "Persons", null);
			for(IndexEntry2 e : ents) {
				logger.info(JSONUtil.exportObject(e, RecordSerializerConfig.getUnfilteredModule()));
			}
		} catch (IndexException e1) {
			logger.error(e1);
		}
		assertTrue("Expected 1 result", ents.length == 1);
		//BaseRecord pdir = ioContext.getPathUtil().makePath(orgContext.getAdminUser(), ModelNames.MODEL_GROUP, "/Persons", "DATA", orgContext.getOrganizationId());
		
		fix.setTrace(false);
		//assertNotNull("PDir is null", pdir);
		BaseRecord user = null;
		try{
			user = mf.getCreateUser(orgContext.getAdminUser(), "Certificate Test User 0", orgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		assertNotNull("User is null", user);
		logger.info(JSONUtil.exportObject(user, RecordSerializerConfig.getUnfilteredModule()));
	}
	*/
	
	
	/// https://stackoverflow.com/questions/11383898/how-to-create-a-x509-certificate-using-java
	/*
	@Test
	public void TestCertificateSetup1() {
		
		CryptoBean crypto = new CryptoBean();
		CryptoFactory.getInstance().generateKeyPair(crypto);
		
		SubjectPublicKeyInfo subPubKeyInfo = SubjectPublicKeyInfo.getInstance(crypto.getPublicKey().getEncoded());
		Instant now = Instant.now();
		Date validFrom = Date.from(now);
		Date validTo = Date.from(now.plusSeconds(60L * 60 * 24 * 365));

		X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
				new X500Name("CN=My Application,O=My Organisation,L=My City,C=DE"),
				    BigInteger.ONE,
				    validFrom,
				    validTo,
				    new X500Name("CN=My Application,O=My Organisation,L=My City,C=DE"),
				    subPubKeyInfo
				);
		
		X509CertificateHolder certificate = null;
		ContentSigner signer;
		try {
			signer = new JcaContentSignerBuilder("SHA256WithRSA")
					.setProvider(new BouncyCastleProvider())
					.build(crypto.getPrivateKey())
			;
			certificate = certBuilder.build(signer);
		} catch (OperatorCreationException e) {
			logger.error(e);
		}
		assertNotNull("Certificate is null", certificate);
	}
	
	
	
	@Test
	public void TestCertificateSetup2() {
		Factory mf = ioContext.getFactory();
		
		BaseRecord user = null;
		try{
			user = mf.getCreateUser(orgContext.getAdminUser(), "Certificate Test User 1", orgContext.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		assertNotNull("User is null", user);
		// logger.info(JSONUtil.exportObject(user, RecordSerializerConfig.getUnfilteredModule()));
		CryptoBean crypto = new CryptoBean();
		CryptoFactory.getInstance().generateKeyPair(crypto);

		X509Certificate certificate2 = null;
		try {
			certificate2 = generate(crypto, user, "SHA256WithRSA", 360);
		} catch (OperatorCreationException | CertificateException | CertIOException e) {
			logger.error(e);
		}
		assertNotNull("Certificate is null", certificate2);
		
	}
	
	private String getDotPath(String path) {
		String[] dots = path.substring(1).split("/");
		List<String> dotl = Arrays.stream(dots).collect(Collectors.toList());
		return dotl.stream().collect(Collectors.joining("."));
	}
	
	/// https://github.com/misterpki/selfsignedcert/blob/master/src/main/java/com/misterpki/SelfSignedCertGenerator.java
	
	public X509Certificate generate(CryptoBean crypto, BaseRecord actor, String hashAlgorithm, int days) throws OperatorCreationException, CertificateException, CertIOException
	{
		Instant now = Instant.now();
		Date notBefore = Date.from(now);
		Date notAfter = Date.from(now.plus(Duration.ofDays(days)));
		
		
		ContentSigner contentSigner = new JcaContentSignerBuilder(hashAlgorithm).build(crypto.getPrivateKey());
		
		String orgPath = getDotPath(actor.get(FieldNames.FIELD_ORGANIZATION_PATH));
		String cn = "CN=" + actor.get(FieldNames.FIELD_NAME);
		X500Name x500Name = new X500Name("CN=" + cn);
		X509v3CertificateBuilder certificateBuilder =
		new JcaX509v3CertificateBuilder(x500Name,
		BigInteger.valueOf(now.toEpochMilli()),
		notBefore,
		notAfter,
		x500Name,
		crypto.getPublicKey())
		.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyId(crypto.getPublicKey()))
		.addExtension(Extension.authorityKeyIdentifier, false, createAuthorityKeyId(crypto.getPublicKey()))
		.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
		
		return new JcaX509CertificateConverter()
		.setProvider(new BouncyCastleProvider()).getCertificate(certificateBuilder.build(contentSigner));
	}
	*/
	private SubjectKeyIdentifier createSubjectKeyId(PublicKey publicKey) throws OperatorCreationException {
		SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
		DigestCalculator digCalc = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
		return new X509ExtensionUtils(digCalc).createSubjectKeyIdentifier(publicKeyInfo);
	}

	private AuthorityKeyIdentifier createAuthorityKeyId(PublicKey publicKey) throws OperatorCreationException {
		SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
		DigestCalculator digCalc = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
		return new X509ExtensionUtils(digCalc).createAuthorityKeyIdentifier(publicKeyInfo);
	}
	
}
