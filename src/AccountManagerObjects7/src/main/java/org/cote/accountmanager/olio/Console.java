package org.cote.accountmanager.olio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Console {
	public static final Logger logger = LogManager.getLogger(Console.class);
	private OlioContext context = null;
	private Pattern consoleLine = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
	private Map<String, IOlioAction> actions = new HashMap<String,IOlioAction>();
	private String prompt = null;
	private CommandLineParser parser = new DefaultParser();
	public Console(OlioContext context) {
		this.context = context;
	}
	
	private boolean isQuit(String line) {
		return (
			line.equalsIgnoreCase("quit")
			||
			line.equalsIgnoreCase("q")
			||
			line.equalsIgnoreCase("exit")
			||
			line.equalsIgnoreCase("e")
		);
	}
	public void listen() {
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try{

			if(prompt == null){
				setPrompt();
			}
			String line = is.readLine();
			
			while (line != null && !isQuit(line)) {
				System.out.print(prompt);
				
				String[] linePar = parseLine(line);
				if(linePar.length > 0 && isQuit(line)) break;
				handleCommandLine(linePar);
				line = is.readLine();
			}
	
		   is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 

	}
	
	private void handleCommandLine(String[] linePar){
		if(linePar.length == 0){
			return;
		}
		String className = "org.cote.rocket.client.action." + linePar[0] + "Action";
		if(actions.containsKey(className) == false){
			ClassLoader classLoader = Console.class.getClassLoader();	
			try {
				Class aClass = classLoader.loadClass(className);
				IOlioAction action = (IOlioAction)aClass.getDeclaredConstructor().newInstance();
				actions.put(className, action);
			} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
				
				logger.error(e.getMessage());
			}
		}
		if(actions.containsKey(className) == false){
			logger.error("Command {0} not found", linePar[0]);
			return;
		}
		CommandLine command = null;
		try {
			Options options = actions.get(className).getCommandLineOptions();
			if(options != null) command = parser.parse( actions.get(className).getCommandLineOptions(), Arrays.copyOfRange(linePar, 1,linePar.length));
		} catch (ParseException e) {
			
			logger.error("Error",e);
		}
		
		actions.get(className).execute(this, command, linePar);
		
	}

	private String[] parseLine(String line){
		List<String> matchList = new ArrayList<String>();
		if(line==null) return new String[0];
		Matcher regexMatcher = consoleLine.matcher(line);
		while (regexMatcher.find()) {
		    if (regexMatcher.group(1) != null) {
		        // Add double-quoted string without the quotes
		        matchList.add(regexMatcher.group(1));
		    } else if (regexMatcher.group(2) != null) {
		        // Add single-quoted string without the quotes
		        matchList.add(regexMatcher.group(2));
		    } else {
		        // Add unquoted word
		        matchList.add(regexMatcher.group());
		    }
		} 
		return matchList.toArray(new String[0]);
	}
	
	public void setPrompt(){
		prompt = "> ";
	}
	
}
