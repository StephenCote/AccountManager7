package org.cote.accountmanager.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.KeyStoreBean;
import org.cote.accountmanager.schema.FieldNames;


/// Derived from prior KeyStoreUtil class, and additional cited examples for using BouncyCastle to generate certificates
/// Also included examples based on:
///    https://github.com/misterpki/selfsignedcert/blob/master/src/main/java/com/misterpki/SelfSignedCertGenerator.java
///    https://gist.github.com/vivekkr12/c74f7ee08593a8c606ed96f4b62a208a

public class CertificateUtil {
	public static final Logger logger = LogManager.getLogger(CertificateUtil.class);
	
	private static final String storeType = "PKCS12";
	private static final String provider = "BC";
	private static final String hashAlgorithm = "SHA256WithRSA";
	private static final String certFactory = "X.509";
	
	private static String getDotPath(String path) {
		if(path == null) {
			return null;
		}
		String[] dots = path.substring(1).split("/");
		List<String> dotl = Arrays.stream(dots).collect(Collectors.toList());
		return dotl.stream().collect(Collectors.joining("."));
	}
	
	public static <T> T decodeCertificate(byte[] certificate){
		T cert = null;
		try{
			CertificateFactory cf = CertificateFactory.getInstance(certFactory);
			ByteArrayInputStream bais = new ByteArrayInputStream(certificate);
			cert =  (T)cf.generateCertificate(bais);
		}
		catch(CertificateException e){
			logger.error(e.getMessage());
			logger.error(e);
		}
		return cert;
	}
	
	public static byte[] toPKCS12(KeyStoreBean bean, String pwd) {
		return toPKCS12(bean, pwd, new KeyStoreBean[0]);
	}
	
	public static byte[] toPKCS12(KeyStoreBean bean, String pwd, KeyStoreBean... chain) {
		String alias = bean.get(FieldNames.FIELD_ALIAS);
		if(alias == null) {
			alias = bean.get(FieldNames.FIELD_NAME);
		}
		Certificate cert = bean.getCertificate();
		CryptoBean crypto = bean.getCryptoBean();
		byte[] p12 = new byte[0];
		if(cert == null) {
			logger.error("Certificate is null");
			return p12;
		}
		if(crypto == null) {
			logger.error("Crypto is null");
			return p12;
		}
		if(crypto.getPrivateKey() == null) {
			logger.error("Private key is null");
			return p12;
		}
		List<Certificate> cchain = new ArrayList<>();
		if(chain != null) {
			for(KeyStoreBean c : chain) {
				if(c != null) {
					if(c.getCertificate() != null) {
						cchain.add(c.getCertificate());
					}
					else {
						logger.error("Null certificate for " + c.get(FieldNames.FIELD_NAME));
					}
				}
			}
		}
		
		try {
			p12 = CertificateUtil.getP12Store(crypto, alias, pwd, cert, cchain.toArray(new Certificate[0]));
		} catch (Exception e) {
			logger.error(e);
			
		}
		return p12;
	}
	
    public static byte[] getP12Store(CryptoBean crypto, String alias, String pass, Certificate cert, Certificate... certChain) throws Exception {
        KeyStore sslKeyStore = KeyStore.getInstance(storeType, provider);
        sslKeyStore.load(null, null);
        List<Certificate> chain = new ArrayList<>();
        for(Certificate c : certChain) {
        	if(c != null) {
        		chain.add(c);
        	}
        }
        chain.add(cert);

        sslKeyStore.setKeyEntry(alias, crypto.getPrivateKey(), (pass != null ? pass.toCharArray() : null), chain.toArray(new Certificate[0]));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        sslKeyStore.store(baos, (pass != null ? pass.toCharArray() : null));
        return baos.toByteArray();
    }
	
	public static KeyStoreBean generate(CryptoBean crypto, KeyStoreBean signerBean, ParameterList parameterList) throws OperatorCreationException, CertificateException, CertIOException
	{
		return generate(new KeyStoreBean(), crypto, signerBean, parameterList);
	}
	public static KeyStoreBean generate(KeyStoreBean store, CryptoBean crypto, KeyStoreBean signerBean, ParameterList parameterList) throws OperatorCreationException, CertificateException, CertIOException
	{
		boolean isCA = false;
		CryptoBean signer = null;
		if(signerBean == null) {
			signer = crypto;
			isCA = true;
		}
		else {
			signer = new CryptoBean(signerBean.get(FieldNames.FIELD_KEY_SET));
		}
		
		int expiryDays = 365;
		String organizationPath = null;
		String vcn = null;
		String domain = null;
		if(parameterList != null) {
			try {
				expiryDays = ParameterUtil.getParameter(parameterList, "expiry", Integer.class, expiryDays);
				vcn = ParameterUtil.getParameter(parameterList, "cn", String.class, null);
				organizationPath = ParameterUtil.getParameter(parameterList, "organization", String.class, null);
				domain = ParameterUtil.getParameter(parameterList, "domain", String.class, null);
			}
			catch(FactoryException e) {
				logger.error(e);
			}
		}
		
		if(vcn == null) {
			logger.error("Must at least define the cn");
			return null;
		}
		
		Instant now = Instant.now();
		Date notBefore = Date.from(now);
		Date notAfter = Date.from(now.plus(Duration.ofDays(expiryDays)));
		
		
		ContentSigner contentSigner = new JcaContentSignerBuilder(hashAlgorithm).build(signer.getPrivateKey());
		
		String orgPath = getDotPath(organizationPath);
		String cn = "CN=" + vcn;
		X500Name x500Name = new X500Name(cn);
		X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(x500Name,
				BigInteger.valueOf(now.toEpochMilli()),
				notBefore,
				notAfter,
				x500Name,
				crypto.getPublicKey()
			)
			.addExtension(Extension.subjectKeyIdentifier, false, createSubjectKeyId(crypto.getPublicKey()))
			.addExtension(Extension.authorityKeyIdentifier, false, createAuthorityKeyId(crypto.getPublicKey()))
			.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCA))
			.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.keyEncipherment))
		;
		if(domain != null) {
			certificateBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, domain)));
		}
		
		X509Certificate cert = new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider()).getCertificate(certificateBuilder.build(contentSigner));
		store.setCertificate(cert);
		store.setCryptoBean(crypto);
		return store;
	}

	private static SubjectKeyIdentifier createSubjectKeyId(PublicKey publicKey) throws OperatorCreationException {
		SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
		DigestCalculator digCalc = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
		return new X509ExtensionUtils(digCalc).createSubjectKeyIdentifier(publicKeyInfo);
	}

	private static AuthorityKeyIdentifier createAuthorityKeyId(PublicKey publicKey) throws OperatorCreationException {
		SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
		DigestCalculator digCalc = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
		return new X509ExtensionUtils(digCalc).createAuthorityKeyIdentifier(publicKeyInfo);
	}
	
	
	// To create a certificate chain we need the issuers' certificate and private key. Keep these together to pass around
	public final static class GeneratedCert {
	    public final PrivateKey privateKey;
	    public final X509Certificate certificate;

	    public GeneratedCert(PrivateKey privateKey, X509Certificate certificate) {
	        this.privateKey = privateKey;
	        this.certificate = certificate;
	    }
	}

	/**
	 * @param cnName The CN={name} of the certificate. When the certificate is for a domain it should be the domain name
	 * @param domain Nullable. The DNS domain for the certificate.
	 * @param issuer Issuer who signs this certificate. Null for a self-signed certificate
	 * @param isCA   Can this certificate be used to sign other certificates
	 * @return Newly created certificate with its private key
	 */
	public static GeneratedCert createCertificate(String cnName, String domain, GeneratedCert issuer, boolean isCA) throws Exception {
	    // Generate the key-pair with the official Java API's
	    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
	    KeyPair certKeyPair = keyGen.generateKeyPair();
	    X500Name name = new X500Name("CN=" + cnName);
	    // If you issue more than just test certificates, you might want a decent serial number schema ^.^
	    BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());
	    Instant validFrom = Instant.now();
	    Instant validUntil = validFrom.plus(10L * 360L, ChronoUnit.DAYS);

	    // If there is no issuer, we self-sign our certificate.
	    X500Name issuerName;
	    PrivateKey issuerKey;
	    if (issuer == null) {
	        issuerName = name;
	        issuerKey = certKeyPair.getPrivate();
	    } else {
	        issuerName = new X500Name(issuer.certificate.getSubjectDN().getName());
	        issuerKey = issuer.privateKey;
	    }

	    // The cert builder to build up our certificate information
	    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
	            issuerName,
	            serialNumber,
	            Date.from(validFrom), Date.from(validUntil),
	            name, certKeyPair.getPublic());

	    // Make the cert to a Cert Authority to sign more certs when needed
	    if (isCA) {
	        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCA));
	    }
	    // Modern browsers demand the DNS name entry
	    if (domain != null) {
	        builder.addExtension(Extension.subjectAlternativeName, false,
	                new GeneralNames(new GeneralName(GeneralName.dNSName, domain)));
	    }

	    // Finally, sign the certificate:
	    ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(issuerKey);
	    X509CertificateHolder certHolder = builder.build(signer);
	    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certHolder);

	    return new GeneratedCert(certKeyPair.getPrivate(), cert);
	}


}
