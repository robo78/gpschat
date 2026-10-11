FROM python:3.12-slim
WORKDIR /srv
COPY --chmod=644 server.py /srv/server.py
RUN useradd --system --uid 10001 gpschat && mkdir /data && chown gpschat /data
USER gpschat
EXPOSE 8080
CMD ["python", "server.py", "--host", "0.0.0.0", "--database", "/data/gpschat.sqlite3"]
