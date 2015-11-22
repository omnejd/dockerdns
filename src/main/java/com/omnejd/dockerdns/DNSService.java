package com.omnejd.dockerdns;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Address;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * Simple DNS server holding a internal record Map.
 *   
 * @author Konrad Eriksson <konrad@konrad.eriksson.com>
 *
 */
public class DNSService extends Thread {
	static final short UDP_LENGTH = 512;
	private static final Logger log = LoggerFactory.getLogger(DNSService.class);
	private final Map<Name, Record> records = new HashMap<Name, Record>();
	private final DatagramSocket sock;
	private final long ttl;
	private final byte [] buff = new byte[UDP_LENGTH];
	private final DatagramPacket indp = new DatagramPacket(buff, buff.length);
	private DatagramPacket outdp = null;
	private boolean shutdown = false;
	
	public DNSService(InetAddress bindAddr, int port, long ttl) throws SocketException {
		super(DNSService.class.getSimpleName());
		this.setDaemon(true);
		if(bindAddr == null) {
			try {
				bindAddr = Address.getByAddress("0.0.0.0");
			} catch (UnknownHostException e) {
				throw new RuntimeException("Failed get address for 0.0.0.0", e);
			}
		}
		sock = new DatagramSocket(port, bindAddr);
		this.ttl = ttl;
	}

	public void shutdown() {
		shutdown = true;
		this.interrupt();
		sock.close();
	}
	
	public void addRecord(String nameStr, InetAddress address) {
		final Name name;
		try {
			name = new Name(nameStr);
		} catch (TextParseException e) {
			throw new IllegalArgumentException("Invalid name: "+nameStr, e);
		}
		final Record rec = new ARecord(name, DClass.IN, ttl, address);
		synchronized(records) {
			records.put(name, rec);
		}
		log.info("Added record: "+rec.getName()+" -> "+rec.rdataToString());
	}
	
	public boolean removeRecord(String nameStr) {
		final Name name;
		try {
			name = new Name(nameStr);
		} catch (TextParseException e) {
			throw new IllegalArgumentException("Invalid name: "+nameStr, e);
		}
		final Record rec;
		synchronized(records) {
			rec = records.remove(name);
		}
		if(rec != null)
			log.info("Removed record: "+rec.getName()+" -> "+rec.rdataToString());
		else
			log.warn("No record found for: "+name);
		return rec != null;
	}

	@Override
	public void run() {
		log.info("DNS service started on "+sock.getLocalAddress().getHostAddress()+":"+sock.getLocalPort());
		while (!shutdown) {
			try {
				receiveAndReply();
			} catch (IOException e) {
				if(shutdown)
					break;
				log.warn("Failed handling request", e);
			}
		}
		log.info("DNS service shutdown");
	}
	
	private void receiveAndReply() throws IOException {
		indp.setLength(buff.length);
		try {
			sock.receive(indp);
		}
		catch (InterruptedIOException e) {
			e.printStackTrace();
			return;
		}
		Message query;
		byte [] response = null;
		try {
			query = new Message(buff);
			response = generateReply(query);
			if (response == null)
				return;
		}
		catch (IOException e) {
			response = formerrMessage(buff);
		}
		if (outdp == null)
			outdp = new DatagramPacket(response,
					response.length,
					indp.getAddress(),
					indp.getPort());
		else {
			outdp.setData(response);
			outdp.setLength(response.length);
			outdp.setAddress(indp.getAddress());
			outdp.setPort(indp.getPort());
		}
		sock.send(outdp);
	}
	
	private byte[] generateReply(Message query) throws IOException {
		Header header;
		int maxLength;

		header = query.getHeader();
		if (header.getFlag(Flags.QR))
			return null;
		if (header.getRcode() != Rcode.NOERROR)
			return errorMessage(query, Rcode.FORMERR);
		if (header.getOpcode() != Opcode.QUERY)
			return errorMessage(query, Rcode.NOTIMP);

		Record queryRecord = query.getQuestion();
		
		OPTRecord queryOPT = query.getOPT();
		if (queryOPT != null && queryOPT.getVersion() > 0)
			errorMessage(query, Rcode.FORMERR);

		if (queryOPT != null)
			maxLength = Math.max(queryOPT.getPayloadSize(), 512);
		else
			maxLength = 512;

		Message response = new Message(query.getHeader().getID());
		response.getHeader().setFlag(Flags.QR);
		if (query.getHeader().getFlag(Flags.RD))
			response.getHeader().setFlag(Flags.RD);
		response.addRecord(queryRecord, Section.QUESTION);

		Name name = queryRecord.getName();
		int type = queryRecord.getType();
		int dclass = queryRecord.getDClass();

		if (!Type.isRR(type) && type != Type.ANY)
			return errorMessage(query, Rcode.NOTIMP);

		byte rcode = addAnswer(response, name, type, dclass, 0, 0);
		if (rcode != Rcode.NOERROR && rcode != Rcode.NXDOMAIN)
			return errorMessage(query, rcode);
		
		return response.toWire(maxLength);
	}
	
	private byte addAnswer(Message response, Name name, int type, int dclass, int iterations, int flags) {
		final Record rec;
		synchronized(records) {
			rec = records.get(name);
		}
		if(rec != null) {
			response.addRecord(rec, Section.ANSWER);
			log.debug("Answering "+name+" > "+rec);
		}
		else {
			log.debug("Answering "+name+" > empty");
		}
		return Rcode.NOERROR;
	}
	
	private byte[] formerrMessage(byte [] in) {
		Header header;
		try {
			header = new Header(in);
		}
		catch (IOException e) {
			return null;
		}
		return buildErrorMessage(header, Rcode.FORMERR, null);
	}

	private byte[] errorMessage(Message query, int rcode) {
		return buildErrorMessage(query.getHeader(), rcode, query.getQuestion());
	}

	private byte[] buildErrorMessage(Header header, int rcode, Record question) {
		Message response = new Message();
		response.setHeader(header);
		for (int i = 0; i < 4; i++)
			response.removeAllRecords(i);
		if (rcode == Rcode.SERVFAIL)
			response.addRecord(question, Section.QUESTION);
		header.setRcode(rcode);
		return response.toWire();
	}

}
