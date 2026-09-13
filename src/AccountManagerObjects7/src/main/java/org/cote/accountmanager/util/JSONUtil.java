/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.accountmanager.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.GeneralException;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

public class JSONUtil {
	public static final Logger logger = LogManager.getLogger(JSONUtil.class);
	
	public static <T> List<T> getList(String data, Class<?> itemClass, SimpleModule module){
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
		
		if(module != null) {
			mapper.registerModule(module);
		}
		CollectionType listType =  mapper.getTypeFactory().constructCollectionType(ArrayList.class, itemClass);
		List<T> lst = null;
		try {
			lst = mapper.readValue(data, listType);
		} catch (IOException e) {
			logger.error(e);
		}
		return lst;
	}
	
	public static <T> Map<String,T> getMap(byte[] data, Class<?> keyClass, Class<?> mapClass){
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
		Map<String,T> map = null;
		try {
			TypeFactory t = TypeFactory.defaultInstance();
			map = mapper.readValue(data, t.constructMapType(Map.class, keyClass, mapClass));
		} catch (IOException e) {
			logger.error(GeneralException.LOGICAL_EXCEPTION, "Error with " + keyClass.getName());
			logger.error(GeneralException.LOGICAL_EXCEPTION,e);
		}
		return map;
		
	}
	/**
	 * Lenient {@link #getMap(byte[], Class, Class)} for parsing text produced by an LLM.
	 * <p>
	 * The strict reader above enables only {@code ALLOW_UNQUOTED_FIELD_NAMES}, which is correct for
	 * model/schema resources we author ourselves: malformed input there is a bug we want to fail on.
	 * LLM output is a different contract — it is generated prose-adjacent text, and every one of the
	 * following is a routine artifact rather than a defect worth discarding a ~90s generation over:
	 * a trailing comma before a closing brace, single-quoted strings, a raw newline inside a long
	 * string value (very common in multi-sentence fields), a {@code //} comment, and a missing value.
	 * <p>
	 * Kept as a SEPARATE method on purpose. Loosening {@link #getMap} itself would silently relax
	 * validation for every model definition and schema resource in the project.
	 *
	 * @param data raw LLM output bytes, ideally after structural repair by the caller
	 * @param errorOut optional single-element array; receives the parser's own message on failure,
	 *        because callers otherwise only see a null return and cannot report WHY it failed
	 * @return the parsed map, or null when the text could not be parsed even leniently
	 */
	public static <T> Map<String,T> getLenientMap(byte[] data, Class<?> keyClass, Class<?> mapClass, String[] errorOut){
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
		mapper.enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature());
		mapper.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature());
		mapper.enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
		mapper.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature());
		mapper.enable(JsonReadFeature.ALLOW_MISSING_VALUES.mappedFeature());
		try {
			TypeFactory t = TypeFactory.defaultInstance();
			return mapper.readValue(data, t.constructMapType(Map.class, keyClass, mapClass));
		} catch (IOException e) {
			/// Deliberately NOT logged at error: the caller records the failure against the
			/// extraction context it owns, and a retry frequently succeeds.
			if (errorOut != null && errorOut.length > 0) {
				errorOut[0] = e.getMessage();
			}
			logger.debug("Lenient JSON parse failed: " + e.getMessage());
		}
		return null;
	}

	public static <T> Map<String,T> getMap(String path, Class<?> keyClass,Class<?> mapClass){
		return getMap(FileUtil.getFile(path),keyClass,mapClass);
	}
	
	public static <T> T importObject(String s,Class<T>  cls){
	    return importObject(s, cls, null);
	}
	public static <T> T importObject(String s, Class<T> cls, SimpleModule module/*JsonDeserializer jdes*/) {
	    
		ObjectMapper mapper = new ObjectMapper();
		mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
        if(module != null) {
            //SimpleModule usageModule = new SimpleModule().addDeserializer(cls, jdes);
            mapper.registerModule(module);
        }
		T outObj = null;
		try {

			outObj = mapper.readValue(s, cls);
		} catch (IOException e) {
			logger.error(e.getMessage());
			logger.error(GeneralException.TRACE_EXCEPTION,e);
		}
		return outObj;
		
	}
	public static <T> String exportObject(T obj){
	    return exportObject(obj, null);
	}
	public static <T> String exportObject(T obj, SimpleModule module){
		return exportObject(obj, module, false, false);
	}
	public static <T> String exportObject(T obj, SimpleModule module, boolean noPrettyPrint, boolean noQuotes){
		ObjectMapper mapper = new ObjectMapper();
		//mapper.enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature());
		if(noQuotes) {
			mapper.disable(JsonWriteFeature.QUOTE_FIELD_NAMES.mappedFeature());
		}
        if(module != null) {
            mapper.registerModule(module);
         }
		mapper.setSerializationInclusion(Include.NON_EMPTY);
		 String outStr = null;
		try {
			ObjectWriter writer = (noPrettyPrint ? mapper.writer() : mapper.writerWithDefaultPrettyPrinter());
			outStr = writer.writeValueAsString(obj);
		} catch (IOException e) {
			logger.error(e.getMessage());
			logger.error(GeneralException.TRACE_EXCEPTION,e);
		}
		return outStr;
	}
}
