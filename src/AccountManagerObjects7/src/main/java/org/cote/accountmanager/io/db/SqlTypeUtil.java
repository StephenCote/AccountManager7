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
package org.cote.accountmanager.io.db;

import java.sql.Types;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.schema.type.SqlDataEnumType;

public class SqlTypeUtil {
	public static final Logger logger = LogManager.getLogger(SqlTypeUtil.class);
	
	public static SqlDataEnumType toSqlType(FieldEnumType fet) {
		SqlDataEnumType outType = SqlDataEnumType.NULL;
		switch(fet) {
			case LONG:
				outType = SqlDataEnumType.BIGINT;
				break;
			case INT:
				outType = SqlDataEnumType.INTEGER;
				break;
			case STRING:
				outType = SqlDataEnumType.VARCHAR;
				break;
			case BOOLEAN:
				outType = SqlDataEnumType.BOOLEAN;
				break;
			case BLOB:
				outType = SqlDataEnumType.BLOB;
				break;
			case DOUBLE:
				outType = SqlDataEnumType.DOUBLE;
				break;
			/*
			case FLOAT:
				outType = SqlDataEnumType.FLOAT;
				break;
			*/
			case TIMESTAMP:
				outType = SqlDataEnumType.TIMESTAMP;
				break;
			case LIST:
				outType = SqlDataEnumType.ARRAY;
				break;
			default:
				logger.error("Unhandled type: " + fet.toString());
				break;
		}
		return outType;
	}
	
	public static SqlDataEnumType translateSqlType(String dataType){
		
		SqlDataEnumType outType = SqlDataEnumType.NULL;
		if(dataType.equals("int8") || dataType.equals("long") || dataType.equals("bigserial")){
			outType = SqlDataEnumType.BIGINT;
		}				
		else if(dataType.startsWith("int") || dataType.equals("serial")){
			outType = SqlDataEnumType.INTEGER;
		}
		else if(dataType.equals("bool")){
			outType = SqlDataEnumType.BOOLEAN;
		}
		else if(dataType.equals("bytea")){
			outType = SqlDataEnumType.BLOB;
		}
		else if(dataType.equals("float8")){
			outType = SqlDataEnumType.DOUBLE;
		}
		if(outType == SqlDataEnumType.NULL){
			try{
				outType = SqlDataEnumType.valueOf(dataType.toUpperCase());
			}
			catch(Exception e){
				logger.error(e.getMessage());
			}
		}
		return outType;
	}

	public static <T> SqlDataEnumType getType(T obj){
		SqlDataEnumType outType = SqlDataEnumType.NULL;
		if(obj instanceof String){
			outType = SqlDataEnumType.VARCHAR;
		}
		else if(obj instanceof Integer){
			outType = SqlDataEnumType.INTEGER;
		}
		else if(obj instanceof Long){
			outType = SqlDataEnumType.BIGINT;
		}
		else if(obj instanceof Boolean){
			outType = SqlDataEnumType.BOOLEAN;
		}
		else if(obj instanceof Date){
			outType = SqlDataEnumType.TIMESTAMP;
		}
		return outType;
	}

	public static int getSqlType(SqlDataEnumType paramType){
		int outType = Types.NULL;

		switch(paramType){
			case ARRAY:
				outType = 2003;
				break;
			case BIGINT:
				outType = -5;
				break;

			case BINARY:
				outType = -2;
				break;
			case BIT:
				outType = -7;
				break;
			case BLOB:
				outType = 2004;
				break;

			case BOOLEAN:
				outType = 16;
				break;
			case CHAR:
				outType = 1;
				break;
			case CLOB:
				outType = 2005;
				break;

			case DATALINK:
				outType = 70;
				break;
			case DATE:
				outType = 91;
				break;
			case DECIMAL:
				outType = 3;
				break;

			case DISTINCT:
				outType = 2001;
				break;
			case DOUBLE:
				outType = 8;
				break;
			case FLOAT:
				outType = 6;
				break;

			case INTEGER:
				outType = 4;
				break;
				
			case JAVA_OBJECT:
				outType = 2004;
				break;
			case LONGVARBINARY:
				outType = -4;
				break;

			case LONGVARCHAR:
				outType = -1;
				break;
			case NULL:
				outType = 0;
				break;
			case NUMERIC:
				outType = 2;
				break;

			case OTHER:
				outType = 1111;
				break;
			case REAL:
				outType = 7;
				break;
			case REF:
				outType = 2006;
				break;

			case SMALLINT:
				outType = 5;
				break;
			case STRUCT:
				outType = 2002;
				break;
			case TIME:
				outType = 92;
				break;

			case TIMESTAMP:
				outType = 93;
				break;
			case TINYINT:
				outType = -6;
				break;
			case VARBINARY:
				outType = -3;
				break;

			case VARCHAR:
				outType = 12;
				break;		
			default:
				logger.warn("Unhandled tye: " + paramType.toString());
				break;
		}
		return outType;
	}
}
