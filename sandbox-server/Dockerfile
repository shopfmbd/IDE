FROM python:3.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends \
      nodejs npm default-jdk-headless build-essential git curl wget unzip zip ca-certificates jq sqlite3 ruby php-cli golang-go \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 10001 sandbox && mkdir /work && chown sandbox /work
COPY server.py /srv/server.py
USER sandbox
ENV SANDBOX_ROOT=/work PORT=8080
EXPOSE 8080
CMD ["python3", "/srv/server.py"]
