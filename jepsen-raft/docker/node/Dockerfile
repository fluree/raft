# Dockerfile for Jepsen test nodes running Fluree Raft
FROM clojure:temurin-21-tools-deps-alpine

# Install runtime dependencies
RUN apk add --no-cache \
    bash \
    openssh \
    supervisor \
    curl \
    net-tools \
    iproute2

# Create fluree user and directories
RUN adduser -D -u 10001 fluree && \
    mkdir -p /opt/fluree-server/data /opt/fluree-server/logs && \
    chown -R fluree:fluree /opt/fluree-server

# Set up SSH for Jepsen
RUN mkdir /var/run/sshd && \
    echo 'root:root' | chpasswd && \
    sed -i 's/#PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config && \
    sed -i 's/#PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config && \
    ssh-keygen -A

# Copy the project files
COPY --chown=fluree:fluree ./raft /opt/fluree-server/raft/
COPY --chown=fluree:fluree ./raft/jepsen-raft /opt/fluree-server/jepsen-raft/

# Set working directory
WORKDIR /opt/fluree-server/jepsen-raft

# Use Docker-specific deps file and pre-download dependencies
RUN cp docker-deps.edn deps.edn && \
    clojure -P && \
    chown -R fluree:fluree /opt/fluree-server/jepsen-raft/.cpcache

# Copy startup script and supervisor config  
COPY --chown=fluree:fluree raft/jepsen-raft/docker/node/start-node.sh /opt/fluree-server/
RUN chmod +x /opt/fluree-server/start-node.sh
COPY raft/jepsen-raft/docker/node/supervisord.conf /etc/supervisord.conf

# Expose ports
# 22 - SSH for Jepsen
# 7001-7005 - Raft TCP ports
EXPOSE 22 7001-7005

# Run supervisor to manage both SSH and Fluree
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisord.conf"]