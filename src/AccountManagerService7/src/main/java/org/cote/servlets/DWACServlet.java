package org.cote.servlets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.MediaOptions;
import org.cote.accountmanager.util.MediaUtil;


public class DWACServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static int defCacheSeconds = 7200;
	public static final Logger logger = LogManager.getLogger(DWACServlet.class);
    public DWACServlet() {
        super();
        
    }

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		long expiry = new Date().getTime() + (defCacheSeconds*1000);
		
		response.setHeader("Cache-Control", "public,max-age="+ defCacheSeconds);
		response.setDateHeader("Expires", expiry);
		response.setCharacterEncoding(StandardCharsets.UTF_8.toString());
	    
		MediaOptions options = new MediaOptions();
		options.setUseTemplate(true);
		options.setEncodeData(true);
		options.setTemplatePath("WEB-INF/resource/dwacTemplate.html");
		options.setTemplateContentType("text/html");
		try{
			MediaUtil.writeBinaryContent(request, response, options);
		}
		catch(IOException e){
			logger.error(e.getMessage());
			response.sendError(404);
		}
	}
	
	@Override
	public long getLastModified(HttpServletRequest req) {
		  return 0;
	}

}