package org.cote.accountmanager.util;

import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CompressionEnumType;

/*
 * ByteModelUtil is largely a holdover from the previous AccountManager 4 - 6 design, where the data object's bytearray was conditionally enciphered, encrypted, and compressed depending on the object settings
 * It remains in place for any object that implements the cryptoByte model, but otherwise will be replaced with field level configuration
 */

public class ByteModelUtil {
   
	public static final Logger logger = LogManager.getLogger(ByteModelUtil.class);

   public static long FIVE_MB_BYTE_LENGTH = 5242880;
   public static long TWO_MB_BYTE_LENGTH = 2097152;
   public static long MAXIMUM_BYTE_LENGTH = TWO_MB_BYTE_LENGTH;
   
   private ByteModelUtil(){

   }
  
   public static void clearCipher(BaseRecord model) throws FieldException, ValueException, ModelNotFoundException{

	   model.set(FieldNames.FIELD_KEYS, null);
   }
 
   public static void setCipher(BaseRecord model, CryptoBean bean) throws FieldException, ValueException, ModelNotFoundException{

       if(bean == null){
           clearCipher(model);
       }
       else{
           CryptoBean useBean =  bean;
           /// Force decryption of a cipher key
           if((boolean)bean.get(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT)){
               useBean = new CryptoBean();
               CryptoFactory.getInstance().setSecretKey(useBean, bean.get(FieldNames.FIELD_CIPHER_FIELD_KEY), bean.get(FieldNames.FIELD_CIPHER_FIELD_IV), false);
           }
           updateCipher(model, bean);
       }
   }
   
   /*
   public static void setPassword(BaseRecord model, String password) throws FieldException, ValueException, ModelNotFoundException{
       if(password == null){
			clearCipher(model);
       }
       else{
           CryptoBean bean = new CryptoBean();
           CryptoFactory.getInstance().setPassKey(bean, password, false);
           updateCipher(model, bean);
       }
   }
   */
   
   private static void updateCipher(BaseRecord model, CryptoBean bean) throws FieldException, ValueException, ModelNotFoundException{
       if(bean == null){
           clearCipher(model);
       }
       else{
    	   model.set(FieldNames.FIELD_KEYS, CryptoFactory.getInstance().holdKey(bean));
       }
   }
   public static void setValue(BaseRecord d, byte[] inValue) throws FieldException, ValueException, ModelNotFoundException
   {
	   setValue(d, FieldNames.FIELD_BYTE_STORE, inValue);
   }
   
   
   public static void setValue(BaseRecord d, String fieldName, byte[] inValue) throws FieldException, ValueException, ModelNotFoundException
   {
	   if(!d.inherits(ModelNames.MODEL_CRYPTOBYTESTORE)) {
		   throw new FieldException("Model doesn't inherit from cryptoByteStore");
	   }
	   /*
	   if(!hasRequiredFields(d)) {
		*/
	   if(!d.hasField(FieldNames.FIELD_BYTE_STORE)) {
		   throw new FieldException("Missing one or more required fields");
	   }
	   
       byte[] value = inValue;
       
       /// directly set value to avoid a stack overflow error
       ///
       d.getField(fieldName).setValue(new byte[0]);
       d.set(FieldNames.FIELD_READ_BYTE_STORE, false);
       boolean vaulted = d.get(FieldNames.FIELD_VAULTED, false);
      
       //byte[] cipherKey = d.get(FieldNames.FIELD_CIPHER_KEY, null);
       String cipherId = (d.hasField(FieldNames.FIELD_KEYS) ? d.get(FieldNames.FIELD_KEYS) : null);

       if(vaulted) {
    	   d.set(FieldNames.FIELD_DATA_HASH, CryptoUtil.getDigestAsString(value, new byte[0]));
           // don't override compression setting for vaulted data
           //
           if(d.hasField(FieldNames.FIELD_COMPRESSION_TYPE)) d.set(FieldNames.FIELD_COMPRESSION_TYPE, CompressionEnumType.NONE.toString());
       }
       //d.set(FieldNames.FIELD_COMPRESSION_TYPE, CompressionEnumType.NONE.toString());

       if (!vaulted && value.length > 512 && tryCompress(d))
       {
           value = ZipUtil.gzipBytes(value);
           d.set(FieldNames.FIELD_COMPRESSION_TYPE, CompressionEnumType.GZIP.toString());
       }

       if (cipherId != null)
       {
    	   CryptoBean bean = CryptoFactory.getInstance().pullKey(cipherId);
    	   //CryptoFactory.getInstance().setMembers(bean, keys, false);
           value = CryptoUtil.encipher(bean, value);
           d.set(FieldNames.FIELD_ENCIPHERED, true);
           /// Zero out the cipher key
           d.set(FieldNames.FIELD_KEYS, null);
       }
       d.set(FieldNames.FIELD_SIZE, (long)value.length);
       d.getField(fieldName).setValue(value);
   }
	public static void setValueString(BaseRecord d, String value) throws FieldException, ValueException, ModelNotFoundException
	{
		if (value != null)
		{
			setValue(d, value.getBytes(StandardCharsets.UTF_8));
		}
		String mt = d.get(FieldNames.FIELD_CONTENT_TYPE);
		if(mt == null || mt.length() == 0){
			d.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
		}
	}
	
	public static String getValueString(BaseRecord d) throws ValueException, FieldException
	{
		return (new String(getValue(d), StandardCharsets.UTF_8));
	}
	
   public static byte[] getValue(BaseRecord d) throws ValueException, FieldException
   {
	   return getValue(d, FieldNames.FIELD_BYTE_STORE);
   }
	   
   public static byte[] getValue(BaseRecord d, String fieldName) throws ValueException, FieldException
   {

	   if(!d.inherits(ModelNames.MODEL_CRYPTOBYTESTORE)) {
		   throw new FieldException("Model doesn't inherit from cryptoByteStore");
	   }
	   if(!d.hasField(FieldNames.FIELD_BYTE_STORE)) {
		   throw new FieldException("Missing one or more required fields");
	   }
	   
	   boolean readDataBytes = d.get(FieldNames.FIELD_READ_BYTE_STORE, false);
	   boolean enciphered = d.get(FieldNames.FIELD_ENCIPHERED, false);
	   boolean vaulted = d.get(FieldNames.FIELD_VAULTED, false);
	   CompressionEnumType compressionType = CompressionEnumType.valueOf(d.get(FieldNames.FIELD_COMPRESSION_TYPE, "NONE"));
	   /// Directly get value to avoid stackoverflow
	   
	   byte[] dbstore = d.getField(fieldName).getValue();
	   
	   if (!readDataBytes && dbstore != null && dbstore.length > 0)
       {
    	   byte[] ret = dbstore;
    	   try
           {
               
               if (enciphered)
               {
                   BaseRecord keys = (d.hasField(FieldNames.FIELD_KEYS) ? d.get(FieldNames.FIELD_KEYS) : null);
            	   if(keys == null || !keys.hasField(FieldNames.FIELD_CIPHER)){
                       throw new ValueException("Cipher key was not specified for enciphered data.");
                   }
            	   CryptoBean bean = new CryptoBean();
            	   CryptoFactory.getInstance().setMembers(bean, keys, false);
                   // CryptoBean bean = CryptoFactory.getInstance().createCryptoBean(cipherKey, false);
                   ret = CryptoUtil.decipher(bean, ret);
                   d.set(FieldNames.FIELD_ENCIPHERED, false);
                   /// Zero out the cipher key
                   d.set(FieldNames.FIELD_KEYS, null);
               }

               if (!vaulted && compressionType.equals(CompressionEnumType.GZIP) && ret.length > 0)
               {
                   ret = ZipUtil.gunzipBytes(ret);
               }
               /// Avoid a stack overflow
               d.getField(fieldName).setValue(ret);
               readDataBytes = true;
               d.set(FieldNames.FIELD_READ_BYTE_STORE, readDataBytes);
           }
           catch (Exception e)
           {
        	   //logger.error(JSONUtil.exportObject(d));
        	   
               throw new ValueException(e.getMessage());
           }
       }
       if (readDataBytes)
       {
    	   /// Directly get field value to avoid stackoverflow
    	   return d.getField(fieldName).getValue();
       }

       return new byte[0];
   }
   public static final int MINIMUM_COMPRESSION_SIZE = 512;
   public static boolean tryCompress(BaseRecord d)
   {
       String contentType = d.get(FieldNames.FIELD_CONTENT_TYPE, null);
       return (
               contentType != null
               &&
               (
                       (
                               contentType.startsWith("image/") == false
                               ||
                               contentType.equals("image/svg+xml")
                       )
                       &&
                       contentType.startsWith("application/") == false
                       &&
                       contentType.startsWith("audio/") == false
                       )
               );

   }

}
