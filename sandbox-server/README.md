# CloudIDE sandbox server

Gives the app's AI assistant a Linux box of its own: it can run commands and code, take zip uploads, unzip them, and hand files back.
Nothing here is exposed to the AI except through this small HTTP door; the Docker container is the sandbox.

## Run it

```sh
cd sandbox-server
docker build -t cloudide-sandbox .
export SANDBOX_TOKEN=$(openssl rand -hex 24)   # keep this: it goes into the app
docker run -d --name sandbox --restart unless-stopped \
  -p 8080:8080 -e SANDBOX_TOKEN \
  --memory 2g --cpus 2 --pids-limit 512 \
  --cap-drop ALL --security-opt no-new-privileges \
  -v sandbox-work:/work cloudide-sandbox
echo $SANDBOX_TOKEN
```

Put it behind HTTPS (Caddy, nginx, Cloudflare Tunnel, ...): the token travels in a header and the AI's commands and files travel in the body.
For example with Caddy: `sandbox.example.com { reverse_proxy localhost:8080 }`.

In the app: Settings > AI sandbox > "Remote server", enter `https://sandbox.example.com` and the token, then tap Test.

## Safety

- Anyone with the token can run commands in the container. Use a long random token, HTTPS, and do not mount anything else into the container.
- Commands run as an unprivileged user with CPU, process and file-size limits; a command is killed after its timeout (default 120 s, at most 600 s).
- Each app install gets its own workspace folder (`/work/<session>`); the AI cannot read outside it through the file calls.
- Network access from the container is whatever Docker gives it. To cut it off, add `--network none` (then nothing can be downloaded either; the port still has to be published through a reverse proxy on the same Docker network instead).

## API (all calls need `Authorization: Bearer <token>`, optional `X-Session`)

| call | purpose |
|---|---|
| `GET /health` | check the server and the token |
| `POST /exec {command, cwd, timeout}` | run `sh -c command` in the workspace |
| `GET /list?path=&depth=` | folder tree |
| `GET /read?path=&max=` | a text file |
| `PUT /file?path=` | upload a file (raw body) |
| `POST /unzip {path, to}` | unpack a zip already in the workspace |
| `GET /download?path=` | a file, or a folder as a zip |
| `POST /reset` | empty the workspace |
