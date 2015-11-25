package com.omnejd.dockerdns;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler of DNS service and Docker monitors
 *   
 * @author Konrad Eriksson <konrad@konrad.eriksson.com>
 *
 */
public class DockerDNS {
	private static final Logger log = LoggerFactory.getLogger(DockerDNS.class);
	/** Polling interval (in sec) for checking Docker containers */
	private int dockerPollInterval = 10;
	private InetAddress dnsBindAddress = null;
	private int dnsPort = 53;
	private int dnsTtl = dockerPollInterval + 1;
	private String dnsDomain = null;
	
	private DNSService dns;
	private Map<URI, DockerMonitor> dockers = new HashMap<URI, DockerMonitor>(); 
		
	public void addDockerServer(URI dockerUri) {
		synchronized (dockers) {
			if(dockers.containsKey(dockerUri))
				throw new IllegalArgumentException("Docker server already configured: "+dockerUri);
			dockers.put(dockerUri, null);
		}
	}
	
	public void setDnsBindAddress(InetAddress dnsBindAddress) {
		this.dnsBindAddress = dnsBindAddress;
	}
	
	public void setDnsPort(int dnsPort) {
		this.dnsPort = dnsPort;
	}
	
	public void setDnsTtl(int dnsTtl) {
		this.dnsTtl = dnsTtl;
	}
	
	public void setDnsDomain(String dnsDomain) {
		this.dnsDomain = dnsDomain;
	}
	
	public void setDockerPollInterval(int dockerPollInterval) {
		this.dockerPollInterval = dockerPollInterval;
		this.dnsTtl = dockerPollInterval + 1;
	}
	
	public void start() throws IOException {
		if(dockers.isEmpty())
			throw new IllegalStateException("No configured Docker servers");
		
		try {
			dns = new DNSService(dnsBindAddress, dnsPort, dnsTtl, dnsDomain);
		}
		catch(SocketException e) {
			throw new IOException("Failed to start DNS service", e);
		}
		dns.start();
		
		synchronized (dockers) {
			if(dockers.isEmpty()) {
				dns.shutdown();
				throw new IllegalStateException("No configured Docker servers");
			}
			
			for(Entry<URI, DockerMonitor> entry : dockers.entrySet()) {
				try {
					DockerMonitor docker = new DockerMonitor(entry.getKey(), dns, dockerPollInterval);
					entry.setValue(docker);
					docker.start();
				}
				catch(IOException e) {
					log.warn("Connect to connect to Docker "+entry.getKey(), e);
				}
			}
		}
	}
	
	public boolean isAlive() {
		return dns.isAlive();
	}
	
	public void shutdown() {
		synchronized (dockers) {
			for(DockerMonitor docker : dockers.values()) {
				if(docker.isAlive())
					docker.shutdown();
			}
			dockers.clear();
		}
		if(dns.isAlive())
			dns.shutdown();
	}
	
}
