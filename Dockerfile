FROM clojure:temurin-17-tools-deps-bookworm-slim

RUN mkdir -p /usr/src/fluree.raft
WORKDIR /usr/src/fluree.raft

# Install the tools we need
RUN apt-get update && apt-get install -y \
    wget \
    curl \
    gnupg2 \
    software-properties-common \
    make \
    git \
    && rm -rf /var/lib/apt/lists/*

# Install clj-kondo for linting
RUN curl -sLO https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo && \
    chmod +x install-clj-kondo && \
    ./install-clj-kondo && \
    rm install-clj-kondo

# Install & cache project deps
COPY deps.edn Makefile ./
RUN make deps

# Copy in the rest of the code
COPY . ./

RUN make jar

# Create a user to own the fluree code
RUN groupadd fluree && useradd --no-log-init -g fluree -m fluree

# move clj deps to fluree's home
# double caching in image layers is unfortunate, but setting this user
# earlier in the build caused its own set of issues
RUN mv /root/.m2 /home/fluree/.m2 && chown -R fluree.fluree /home/fluree/.m2
RUN mv /root/.gitlibs /home/fluree/.gitlibs 2>/dev/null || true && chown -R fluree.fluree /home/fluree/.gitlibs 2>/dev/null || true

RUN chown -R fluree.fluree .
USER fluree

ENTRYPOINT []