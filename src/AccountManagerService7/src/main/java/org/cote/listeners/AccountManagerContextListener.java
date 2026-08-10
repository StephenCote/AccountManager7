package org.cote.listeners;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderWriterSpi;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.sockets.WebSocketService;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

public class AccountManagerContextListener implements ServletContextListener{
	public static final Logger logger = LogManager.getLogger(AccountManagerContextListener.class);

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		logger.info("Closing proxy sessions");
		WebSocketService.closeProxySessions();

		logger.info("Chirping users");
		WebSocketService.activeUsers().forEach(user ->{
			WebSocketService.chirpUser(user, new String[] {"Service going offline"});
		});

		deregisterImageIOProviders();

		logger.info("Context destroyed");
	}

	//Run this before web application is started
	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		/// Do NOT let ImageIO use its disk cache.
		///
		/// The cache path is what drags in the FileCacheImageInputStream/Channels machinery LAZILY,
		/// during whatever request happens to be running. If the webapp has since been stopped, the
		/// class load is refused by Tomcat's classloader and surfaces as the misleading
		///   NoClassDefFoundError: ... Illegal access: this web application instance has been
		///   stopped already. Could not load [java.nio.channels.Channels]
		/// from deep inside ImageIO.read (reported by Stephen 2026-08-10, via
		/// GraphicsUtil.createThumbnail <- ThumbnailUtil <- MediaUtil <- ThumbnailServlet).
		/// Memory caching removes that lazy filesystem path entirely; these are thumbnails, not
		/// gigapixel scans.
		ImageIO.setUseCache(false);

		/// Load the plugins ONCE, here, under this webapp's classloader, instead of leaving each
		/// codec to be discovered lazily on a request thread.
		ImageIO.scanForPlugins();

		logger.info("Context initialized (ImageIO disk cache disabled, plugins scanned)");
	}

	/**
	 * Actually deregister this webapp's ImageIO service providers.
	 *
	 * <p>This used to call {@code ImageIO.scanForPlugins()} under the log line "Deregistering ImageIO
	 * service providers to prevent ClassLoader leaks" — which does the exact OPPOSITE: it REGISTERS
	 * providers. {@code IIORegistry.getDefaultInstance()} is keyed per ThreadGroup and long outlives a
	 * webapp, so scanning on the way out pinned this webapp's classloader (and the TwelveMonkeys SPI
	 * classes) into a registry that survives the undeploy. A later request or a lingering thread then
	 * touched a class through a stopped classloader, which is the "this web application instance has
	 * been stopped already" error.
	 *
	 * <p>Removes only providers whose implementation class was loaded by THIS webapp's classloader, so
	 * the JDK's own codecs and anything supplied by the container are left alone. Never throws — a
	 * cleanup failure must not break undeploy.
	 */
	private void deregisterImageIOProviders() {
		try {
			ClassLoader webappLoader = Thread.currentThread().getContextClassLoader();
			IIORegistry registry = IIORegistry.getDefaultInstance();
			List<Object> doomed = new ArrayList<>();

			Iterator<Class<?>> categories = registry.getCategories();
			while(categories.hasNext()) {
				Class<?> category = categories.next();
				Iterator<?> providers = registry.getServiceProviders(category, false);
				while(providers.hasNext()) {
					Object provider = providers.next();
					if(provider != null && provider.getClass().getClassLoader() == webappLoader) {
						doomed.add(provider);
					}
				}
			}

			for(Object provider : doomed) {
				String name = provider.getClass().getName();
				if(provider instanceof ImageReaderWriterSpi) {
					name = name + " (" + ((ImageReaderWriterSpi)provider).getDescription(null) + ")";
				}
				registry.deregisterServiceProvider(provider);
				logger.info("Deregistered ImageIO provider: " + name);
			}
			logger.info("Deregistered " + doomed.size() + " ImageIO service provider(s) loaded by this webapp");
		}
		catch(Exception e) {
			/// Cleanup must never prevent the context from shutting down.
			logger.warn("Failed to deregister ImageIO service providers: " + e.getMessage());
		}
	}
}
