package org.cote.accountmanager.olio.assist;

import java.nio.file.Path;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;

public interface IAssist {
	public void processModified(Path path);
	public int summarize(Path path, String content) throws FieldException, ModelNotFoundException;
	public void assist(Path path, String prev, String line, String next) throws FieldException, ModelNotFoundException;
}
