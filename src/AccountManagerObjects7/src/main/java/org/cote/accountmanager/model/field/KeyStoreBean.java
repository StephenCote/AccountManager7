package org.cote.accountmanager.model.field;

import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.CertificateUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class KeyStoreBean extends LooseRecord{
	public static final Logger logger = LogManager.getLogger(KeyStoreBean.class);
	
	@JsonIgnore
	private Certificate certificate = null;
	
	private CryptoBean cryptoBean = null;
	
	public KeyStoreBean() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_KEY_STORE, this, null);
			
		} catch (FieldException | ModelNotFoundException e) {
			/// ignore
		}
	}
	
	public KeyStoreBean(BaseRecord bean) {
		this();
		this.setFields(bean.getFields());
	}
	
	@JsonIgnore
	public void setCryptoBean(CryptoBean bean) {
		this.cryptoBean = bean;
		try {
			this.set(FieldNames.FIELD_KEY_SET, bean);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	@JsonIgnore
	public CryptoBean getCryptoBean() {
		if(cryptoBean == null && hasField(FieldNames.FIELD_KEY_SET)) {
			cryptoBean = new CryptoBean(get(FieldNames.FIELD_KEY_SET));
		}
		return cryptoBean;
	}
	
	@JsonIgnore
	public Certificate getCertificate() {
		if(certificate == null && hasField(FieldNames.FIELD_STORE)) {
			byte[] certBytes = get(FieldNames.FIELD_STORE);
			certificate = CertificateUtil.decodeCertificate(certBytes);
		}
		return certificate;
	}
	
	@JsonIgnore
	public void setCertificate(Certificate certificate) {
		this.certificate = certificate;
		byte[] certBytes = new byte[0];
		try {
			if(certificate != null) {
				certBytes = certificate.getEncoded();
			}
			set(FieldNames.FIELD_STORE, certBytes);
		}
		catch(FieldException | ValueException | ModelNotFoundException | CertificateEncodingException e) {
			logger.error(e);
		}
	}
	
	@JsonIgnore
	public byte[] toPKCS12(String pwd) {
		return CertificateUtil.toPKCS12(this, pwd);
	}
	
 
	
}
