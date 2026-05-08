FROM amazonlinux:2023

RUN dnf install -y awscli gzip findutils \
  && dnf clean all \
  && rm -rf /var/cache/dnf

ENTRYPOINT ["/bin/sh", "/app/scripts/s3-recording-sync.sh"]
