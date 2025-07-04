# Dockerfile for Jepsen control node
FROM clojure:temurin-21-tools-deps-bullseye

# Install dependencies
RUN apt-get update && apt-get install -y \
    openssh-client \
    gnuplot \
    graphviz \
    curl \
    wget \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set up SSH client config
RUN mkdir -p /root/.ssh && \
    echo "Host *\n\tStrictHostKeyChecking no\n\tUserKnownHostsFile /dev/null" > /root/.ssh/config && \
    chmod 600 /root/.ssh/config

# Set working directory
WORKDIR /jepsen-raft

# Keep container running
CMD ["bash"]