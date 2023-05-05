package org.cote.accountmanager.exceptions;

public class ModelNotFoundException extends Exception {

		private static final long serialVersionUID = 1L;
		public static final String NOT_FOUND = "Model %s was not found.";
		
		public ModelNotFoundException(String msg){
			super(msg);
		}

}
