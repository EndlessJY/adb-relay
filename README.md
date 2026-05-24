# ADB Relay

ADB Relay is a small TCP reverse relay that exposes a real Android `adbd` from a phone to a VPS or code-server environment.

It does not implement the ADB protocol. It only forwards bytes between:

- a VPS-local ADB port, for example `127.0.0.1:40001`
- an Android agent app that dials out to the VPS
- the phone's own `adbd`, for example `127.0.0.1:5555` or the current wireless debugging port

```text
code-server / VPS
  adb connect 127.0.0.1:40001
        |
        v
Docker container: adb-relay-server
  0.0.0.0:7000   Android agent connects here
  0.0.0.0:40001  ADB client connects here through host loopback
        |
        v
Android Agent app
  connects to phone-local adbd
```

## Repository Layout

```text
server/         Node.js relay server
android-agent/ Android foreground-service agent app
Dockerfile      Container image for the relay server
docker-compose.yml
```

## Security Model

Treat every ADB port as sensitive. Anyone who can reach the ADB listener can operate the phone through `adb`.

Recommended defaults:

- expose `7000/tcp` only to devices that need to run the Android agent
- keep the ADB listener bound to host loopback with Docker port mapping like `127.0.0.1:40001:40001`
- use a long random `ADB_RELAY_TOKEN`
- put the relay behind WireGuard, Tailscale, SSH tunnel, or firewall rules when possible

The current relay handshake uses a shared token over plain TCP. Do not treat it as a public, zero-trust service.

## Docker Deployment

### 1. Build the image

```bash
docker build -t adb-relay:local .
```

### 2. Create `.env`

```bash
cp .env.example .env
```

Edit `.env`:

```env
ADB_RELAY_TOKEN=replace-with-a-long-random-token
ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000
ADB_RELAY_ADB_LISTEN=0.0.0.0:40001
DEVICE_RELAY_PORT=7000
ADB_RELAY_PORT=40001
```

Inside Docker, the server should listen on `0.0.0.0`. The Compose file already limits the host-side ADB port to `127.0.0.1`.

### 3. Start the relay

```bash
docker compose up -d --build
docker compose logs -f adb-relay
```

Expected logs:

```text
[listen] device relay on 0.0.0.0:7000
[listen] adb local port on 0.0.0.0:40001
```

### 4. Open the firewall

Open only the Android-agent port on the VPS:

```bash
ufw allow 7000/tcp
```

Do not open `40001/tcp` publicly. In `docker-compose.yml`, it is published as:

```yaml
ports:
  - "127.0.0.1:40001:40001"
```

That means code-server or shell sessions on the VPS can connect to it locally, but the public internet cannot.

## Android Agent

Build and install the Android app:

```bash
cd android-agent
./build.sh
adb install -r adb-relay-agent-debug.apk
```

The build script uses Android SDK command-line tools directly. If your SDK is not in the default location:

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

Open the app and fill:

```text
VPS host        VPS public IP or domain
VPS relay port  7000
Device id       phone-1
Token           same value as ADB_RELAY_TOKEN
adbd host       127.0.0.1
adbd port       5555 or the wireless debugging port
```

Then tap `Start relay`.

## Connect From VPS or code-server

Install ADB on the VPS:

```bash
apt update
apt install -y android-tools-adb
adb version
```

Connect:

```bash
adb connect 127.0.0.1:40001
adb devices -l
adb -s 127.0.0.1:40001 shell getprop ro.product.model
```

## Multi-Device Mode

Each phone needs a stable `Device id` and a dedicated ADB port.

Example `.env`:

```env
ADB_RELAY_TOKEN=replace-with-a-long-random-token
ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000
ADB_RELAY_DEVICES=phone-1:0.0.0.0:40001,phone-2:0.0.0.0:40002
DEVICE_RELAY_PORT=7000
```

Add the second ADB port to `docker-compose.yml`:

```yaml
ports:
  - "7000:7000"
  - "127.0.0.1:40001:40001"
  - "127.0.0.1:40002:40002"
```

Start the service:

```bash
docker compose up -d --build
```

Connect from the VPS:

```bash
adb connect 127.0.0.1:40001
adb connect 127.0.0.1:40002
adb devices -l
```

The Android app `Device id` values must match `phone-1` and `phone-2`.

## Run Without Docker

```bash
cd server
npm test
node src/cli.js \
  --token 'replace-with-a-long-random-token' \
  --device-listen 0.0.0.0:7000 \
  --adb-listen 127.0.0.1:40001
```

Multi-device mode:

```bash
node src/cli.js \
  --token 'replace-with-a-long-random-token' \
  --device-listen 0.0.0.0:7000 \
  --device phone-1:127.0.0.1:40001 \
  --device phone-2:127.0.0.1:40002
```

## Environment Variables

The Docker image and CLI both support these variables:

```text
ADB_RELAY_TOKEN          Required shared token
ADB_RELAY_DEVICE_LISTEN  Android-agent listener, default 0.0.0.0:7000
ADB_RELAY_ADB_LISTEN     Single-device ADB listener, default 127.0.0.1:40001
ADB_RELAY_DEVICES        Comma-separated multi-device mappings
```

If `ADB_RELAY_DEVICES` is set, it takes multi-device mode and `ADB_RELAY_ADB_LISTEN` is ignored.

## GitHub Container Registry

The workflow at `.github/workflows/docker-image.yml` builds the image on pull requests and pushes images to GHCR on `main` and `v*` tags:

```text
ghcr.io/endlessjy/adb-relay:latest
ghcr.io/endlessjy/adb-relay:main
ghcr.io/endlessjy/adb-relay:vX.Y.Z
```

Use the published image:

```bash
docker run -d \
  --name adb-relay \
  --restart unless-stopped \
  -e ADB_RELAY_TOKEN='replace-with-a-long-random-token' \
  -e ADB_RELAY_DEVICE_LISTEN='0.0.0.0:7000' \
  -e ADB_RELAY_ADB_LISTEN='0.0.0.0:40001' \
  -p 7000:7000 \
  -p 127.0.0.1:40001:40001 \
  ghcr.io/endlessjy/adb-relay:latest
```

## Troubleshooting

Check container logs:

```bash
docker compose logs -f adb-relay
```

Confirm listeners:

```bash
ss -ltnp | grep -E '7000|40001'
```

If `adb connect` succeeds but commands fail, check:

- Android agent notification status
- phone wireless debugging port
- `ADB_RELAY_TOKEN` matches the app Token field
- `Device id` matches the mapped device id in multi-device mode
- VPS firewall exposes `7000/tcp`
- ADB port is not exposed publicly
