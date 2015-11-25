package com.omnejd.dockerdns;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.Version;

/**
 * Monitor for a single Docker instance.
 *   
 * @author Konrad Eriksson <konrad@konrad.eriksson.com>
 *
 */
public class DockerMonitor extends Thread {
	private static final Logger log = LoggerFactory.getLogger(DockerMonitor.class);
	private static final int MAX_FAILURES = 3;
	private final DNSService dns;
	private final URI dockerUri;
	private final DockerClient docker;
	private final Version version;
	private final int pollInterval;
	private boolean shutdown = false;
	private int failures = 0;
	private Map<String, String> hosts = new HashMap<String, String>();
	
	public DockerMonitor(URI dockerUri, DNSService dns, int pollInterval) throws IOException {
		super(dockerUri.getHost()+":"+dockerUri.getPort());
		setDaemon(true);
		this.dns = dns;
		this.dockerUri = dockerUri;
		docker = new DefaultDockerClient(dockerUri);
		try {
			version = docker.version();
		} catch (Exception e) {
			throw new IOException("Failed to request version", e);
		}
		this.pollInterval = pollInterval;
	}
	
	public synchronized void shutdown() {
		shutdown = true;
		this.interrupt();
	}

	@Override
	public void run() {
		log.info("Started monitoring: "+dockerUri+" "+version);
		while(!shutdown) {
			try {
				checkForChanges();
				failures = 0;
			} catch (Exception e) {
				if(shutdown)
					break;
				log.warn("Request to Docker failed", e);
				failures++;
				if(failures == MAX_FAILURES) {
					log.error("Reached "+MAX_FAILURES+" consecutive failures, shutting down");
					return;
				}
			}
			if(shutdown)
				break;
			try {
				Thread.sleep(pollInterval * 1000L);
			} catch (InterruptedException e) {}
		}
		log.info("Docker monitor shutdown");
	}
	
	private void checkForChanges() throws DockerException, InterruptedException, UnknownHostException {
		Map<String, String> newHosts = list();
		
		if(!newHosts.equals(hosts)) {
			Map<String, String> removed = new HashMap<String, String>(hosts);
			removed.keySet().removeAll(newHosts.keySet());
			Map<String, String> added = new HashMap<String, String>(newHosts);
			added.keySet().removeAll(hosts.keySet());

			log.debug("Running containers changed. removed:"+removed+" added:"+added);
			
			for(Entry<String, String> entry : removed.entrySet()) {
				dns.removeRecord(entry.getKey());
			}
			for(Entry<String, String> entry : added.entrySet()) {
				dns.addRecord(entry.getKey(), InetAddress.getByName(entry.getValue()));
			}
			hosts = newHosts;
		}
	}
	
	private Map<String, String> list() throws DockerException, InterruptedException {
		Map<String, String> map = new HashMap<String, String>();
		for(Container cont : docker.listContainers()) {
			ContainerInfo info = docker.inspectContainer(cont.id());
			map.put(info.config().hostname(), info.networkSettings().ipAddress());
		}
		return map;
	}
	
}