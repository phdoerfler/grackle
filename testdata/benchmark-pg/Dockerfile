# testdata/benchmark-pg/Dockerfile
FROM postgres:11.8

# postgres:11.8 is based on Debian Stretch, which is EOL: its apt repos
# (deb.debian.org, security.debian.org) have been pulled from the live
# mirror network, and the pgdg apt repo no longer serves stretch-pgdg.
# Repoint at archive.debian.org and drop pgdg (not needed for the
# packages installed below) so apt-get update/install still work.
RUN rm -f /etc/apt/sources.list.d/pgdg.list && \
    sed -i \
      -e 's|deb.debian.org|archive.debian.org|g' \
      -e 's|security.debian.org/debian-security|archive.debian.org/debian-security|g' \
      -e '/stretch-updates/d' \
      /etc/apt/sources.list && \
    echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/99no-check-valid-until

RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip ruby dos2unix curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir /data
RUN curl -sL -o /data/install.sql https://raw.githubusercontent.com/lorint/AdventureWorks-for-Postgres/master/install.sql
RUN curl -sL -o /data/update_csvs.rb https://raw.githubusercontent.com/lorint/AdventureWorks-for-Postgres/master/update_csvs.rb
RUN curl -sL -o /data/adventure_works_2014_OLTP_script.zip https://github.com/Microsoft/sql-server-samples/releases/download/adventureworks/AdventureWorks-oltp-install-script.zip

RUN cd /data && \
    unzip -o adventure_works_2014_OLTP_script.zip && \
    rm adventure_works_2014_OLTP_script.zip && \
    ruby update_csvs.rb && \
    rm update_csvs.rb

COPY install.sh /docker-entrypoint-initdb.d/10-install.sh
RUN dos2unix /docker-entrypoint-initdb.d/10-install.sh && chmod +x /docker-entrypoint-initdb.d/10-install.sh

WORKDIR /data/
