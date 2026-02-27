# syntax=docker/dockerfile:1.2

FROM python:3.12-slim-bullseye

RUN apt-get update && \
    apt-get install -y \
      git \
      openssh-client \
      build-essential \
      gcc \
      wget \
      curl \
      dpkg \
      ca-certificates \
      gnupg \
      aria2 \
      mediainfo \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN python3 -m venv /app/streamvenv

COPY requirements.txt .
RUN /app/streamvenv/bin/pip install --no-cache-dir --upgrade pip setuptools wheel && \
    /app/streamvenv/bin/pip install --no-cache-dir -r requirements.txt

COPY . .

COPY start.sh /app/start.sh
RUN chmod +x /app/start.sh

EXPOSE 8000

CMD ["bash", "/app/start.sh"]