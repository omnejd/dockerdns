# dockerdns
Simple DNS service for Docker containers

[![Build Status](https://travis-ci.org/omnejd/dockerdns.svg?branch=master)](https://travis-ci.org/omnejd/dockerdns)

#### Idea
Use DNS for basic Docker "container discovery" by returning running container's internal IP to DNS query matching container hostname.

By using old-fashioned DNS protocol with low TTL this enables looking up containers by their hostname outside Docker (and also from other containers without need of linking them). 

With dockerdns on each node in a Docker cluster together with central DNS server relaying to each dockerdns this can be used to find containers no matter what Docker host they are deployed on.

This is probably only useful in scenarios where you have internal IP of containers routable to/from external network (that is you have forwarding enabled for the virtual subnet enabled by docker0 and rest of your external network knows how to route this subnet to the Docker host).

Nice side-effect is that also non-containerized services can lookup services/containers based on their hostname without need of any 3rd party client/agent (just include the IP of dockerdns in /etc/resolv.conf)

#### How it works

It connects to one or more Docker services via Docker API and polls for changed in running containers.
For each running container it keeps a internal DNS record containing [container.hostname] -> [container.internal.ip].
It also acts a simple DNS server and responds to each query matching a known container.hostname with container's internal IP. 


#### Building
To generate a "fat jar" with all dependencies included run:
```
maven package
```

#### Run
It requires Java7 to run.

Start the DNS service by specifying at least one Docker instance:
```
java -jar dockerdns-...jar http://dockerhost:4342
```

It's possible to specify multiple Docker instances to monitor:
```
java -jar dockerdns-...jar http://dockerhost:4342 http://dockerhost2:4342
```

Get additional help on options by running it with `--help` or without any arguments.


#### TODOs
* Add javadoc
* Write tests
* Listen to Docker events instead of polling
* Support for HTTPS and authenticated Docker API access
* Re-connect to Docker servers that went missing and came back
* Implement sharing of DNS entries between multiple dockerdns instances (could get rid of 'central DNS server' topology)

#### Acknowledgment
dockerdns is using [dnsjava](https://github.com/dnsjava/dnsjava) by Brian Wellington for DNS protocol implementation and [docker-client](https://github.com/spotify/docker-client) by Spotify for Docker API client support.
