package com.omnejd.dockerdns;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;

import org.slf4j.LoggerFactory;

import static java.util.Arrays.*;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * Main entrypoint class when running bundled jar. It parses cmd-line args and start service.
 *   
 * @author Konrad Eriksson <konrad@konrad.eriksson.com>
 *
 */
public class Main {

	public static void main(String[] args) throws IOException {
		String dnsDomain = "docker";
		OptionParser parser = new OptionParser();
		parser.accepts("dnsBind", "Interface to bind DNS service").withRequiredArg().defaultsTo("0.0.0.0");
		parser.accepts("dnsPort", "UDP port DNS service listens on").withRequiredArg().ofType(Integer.class).defaultsTo(53);
		parser.accepts("dnsDomain", "Domain part to add on container names").withRequiredArg().ofType(String.class).defaultsTo(dnsDomain);
		parser.accepts("debug", "Turn on debug logging");
		parser.acceptsAll(asList("h", "help"), "Show this help");

		OptionSet options = parser.parse(args);
		if(options.has("h") || options.nonOptionArguments().isEmpty()) {
			System.out.println("DockerDNS - Simple DNS service for Docker containers");
			System.out.println("Arguments: [options] http://dockerhost:port");
			System.out.println();
			parser.printHelpOn(System.out);
			System.exit(1);
		}
		
		if(options.has("debug")) {
			System.getProperties().setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
			LoggerFactory.getLogger(Main.class).debug("Enabled debug logging");
		}

		final DockerDNS ddns = new DockerDNS();
		
		for(Object arg : options.nonOptionArguments()) {
			URI dockerUri = URI.create(arg.toString());
			ddns.addDockerServer(dockerUri);
		}
		
		if(options.has("dnsBind")) {
			ddns.setDnsBindAddress(InetAddress.getByName(options.valueOf("dnsBind").toString()));
		}
		if(options.has("dnsPort")) {
			ddns.setDnsPort((Integer) options.valueOf("dnsPort"));
		}
		if(options.has("dnsDomain")) {
			dnsDomain = (String) options.valueOf("dnsDomain");
		}
		ddns.setDnsDomain(dnsDomain);
		
		ddns.start();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				ddns.shutdown();
			}
		});

		while(ddns.isAlive()) {
			try {
				Thread.sleep(1000L);
			} catch (InterruptedException e) {}
		}
		ddns.shutdown();
		System.exit(1);
	}

}
