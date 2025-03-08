package org.cote.accountmanager.olio.assist;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.DocumentUtil;

public abstract class DocumentMap {
	public final Logger logger = LogManager.getLogger(DocumentMap.class);

	protected Map<Path, List<String>> documentMap = new HashMap<>();
	protected Map<Path, String> documentSummary = new HashMap<>();
	protected List<Path> modifiedFiles = new ArrayList<>();
	protected Map<Path, Long> processedFiles = new HashMap<>();

	public DocumentMap() {

	}

	public void mapRtfDocument(Path path) {
		String txt = DocumentUtil.readRtf(path.toString());
		if (txt == null) {
			logger.error("Null content for " + path);
			return;
		} else if (txt.trim().length() == 0) {
			logger.info("Skip empty document " + path);
			return;
		}
		List<String> lines = Arrays.asList(txt.split("\n")).stream().map(l -> l.trim()).collect(Collectors.toList());
		documentMap.put(path, lines);
	}

}
