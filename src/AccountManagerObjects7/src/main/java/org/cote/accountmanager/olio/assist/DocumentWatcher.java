package org.cote.accountmanager.olio.assist;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.thread.Threaded;

public class DocumentWatcher extends Threaded {
	public static final Logger logger = LogManager.getLogger(DocumentWatcher.class);
	private int threadDelay = 250;

	private IAssist assistant = null;

	private String path = null;

	WatchService watchSvc = null;

	public DocumentWatcher(IAssist assistant, String path) {
		this.assistant = assistant;
		this.path = path;
		this.setThreadDelay(threadDelay);
		try {
			configureWatch();
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	private void configureWatch() throws IOException {
		Path dPath = Paths.get(path);

		watchSvc = FileSystems.getDefault().newWatchService();
		Files.walkFileTree(dPath, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				dir.register(watchSvc, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
						StandardWatchEventKinds.ENTRY_MODIFY);
				return FileVisitResult.CONTINUE;
			}
		});

	}

	@Override
	public void execute() {
		if (assistant == null || path == null || watchSvc == null) {
			return;
		}
		try {

			WatchKey key = watchSvc.take();

			for (WatchEvent<?> event : key.pollEvents()) {
				// Handle the specific event
				if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
					System.out.println("File created: " + event.context());
				} else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
					System.out.println("File deleted: " + event.context());
				} else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
					String name = event.context().toString();
					if (name.equals("content.rtf")) {
						Path dir = (Path) key.watchable();

						Path fullPath = dir.resolve(name);
						assistant.processModified(fullPath);
					}
				}
			}
			key.reset();
		}

		catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

}
