# ADB Relay

中文：ADB Relay 是一个轻量 TCP 反向代理，用来把 Android 手机上真实运行的 `adbd` 暴露给 VPS 或 code-server 环境使用。

English: ADB Relay is a lightweight TCP reverse relay that exposes a real Android `adbd` from a phone to a VPS or code-server environment.

中文：它不实现 ADB 协议，只转发 TCP 字节流。真正执行 `shell`、`install`、`logcat`、`push`、`pull` 的仍然是手机系统里的 `adbd`。

English: It does not implement the ADB protocol. It only forwards TCP byte streams. Commands such as `shell`, `install`, `logcat`, `push`, and `pull` are still handled by the phone's own `adbd`.

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

## 项目结构 / Repository Layout

```text
server/         Node.js relay server / Node.js 中继服务端
android-agent/ Android foreground-service agent app / Android 前台服务 App
Dockerfile      Container image for the relay server / 服务端容器镜像
docker-compose.yml
```

## 安全模型 / Security Model

中文：所有 ADB 入口都要按高风险端口处理。任何能访问 ADB 监听端口的人，都可能通过 `adb` 操作你的手机。

English: Treat every ADB listener as sensitive. Anyone who can reach the ADB port may be able to operate the phone through `adb`.

推荐默认配置 / Recommended defaults:

- 中文：只把 `7000/tcp` 开放给需要运行 Android Agent 的设备。  
  English: Expose `7000/tcp` only to devices that need to run the Android agent.
- 中文：ADB 端口保持在宿主机本地回环，例如 Docker 映射 `127.0.0.1:40001:40001`。  
  English: Keep the ADB listener on host loopback, for example with Docker port mapping `127.0.0.1:40001:40001`.
- 中文：使用足够长、随机的 `ADB_RELAY_TOKEN`。  
  English: Use a long random `ADB_RELAY_TOKEN`.
- 中文：尽量放在 WireGuard、Tailscale、SSH tunnel 或防火墙之后。  
  English: Put the relay behind WireGuard, Tailscale, an SSH tunnel, or firewall rules whenever possible.

中文：当前握手使用明文 TCP 上的共享 token，不要把它当作公开零信任服务。

English: The current handshake uses a shared token over plain TCP. Do not treat it as a public zero-trust service.

## Docker 部署 / Docker Deployment

### 1. 构建镜像 / Build the image

```bash
docker build -t adb-relay:local .
```

### 2. 创建 `.env` / Create `.env`

```bash
cp .env.example .env
```

编辑 `.env` / Edit `.env`:

```env
ADB_RELAY_TOKEN=replace-with-a-long-random-token
ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000
ADB_RELAY_ADB_LISTEN=0.0.0.0:40001
DEVICE_RELAY_PORT=7000
ADB_RELAY_PORT=40001
```

中文：在 Docker 容器内，服务端应监听 `0.0.0.0`。Compose 文件已经把宿主机侧 ADB 端口限制到 `127.0.0.1`。

English: Inside Docker, the server should listen on `0.0.0.0`. The Compose file already limits the host-side ADB port to `127.0.0.1`.

### 3. 启动中继 / Start the relay

```bash
docker compose up -d --build
docker compose logs -f adb-relay
```

预期日志 / Expected logs:

```text
[listen] device relay on 0.0.0.0:7000
[listen] adb local port on 0.0.0.0:40001
```

### 4. 开放防火墙 / Open the firewall

中文：VPS 上只需要开放 Android Agent 连接的端口。

English: On the VPS, only open the port used by the Android agent.

```bash
ufw allow 7000/tcp
```

中文：不要把 `40001/tcp` 公开到公网。`docker-compose.yml` 默认这样发布：

English: Do not expose `40001/tcp` to the public internet. `docker-compose.yml` publishes it like this by default:

```yaml
ports:
  - "127.0.0.1:40001:40001"
```

中文：这表示 VPS 本机的 code-server 或 shell 可以连接它，但公网不能直接访问。

English: This allows code-server or shell sessions on the VPS to connect locally, while the public internet cannot reach it directly.

## Android Agent

构建并安装 Android App / Build and install the Android app:

```bash
cd android-agent
./build.sh
adb install -r adb-relay-agent-debug.apk
```

中文：构建脚本直接使用 Android SDK command-line tools。如果 SDK 不在默认位置：

English: The build script uses Android SDK command-line tools directly. If your SDK is not in the default location:

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

打开 App 并填写 / Open the app and fill:

```text
VPS host        VPS public IP or domain / VPS 公网 IP 或域名
VPS relay port  7000
Device id       phone-1
Token           same value as ADB_RELAY_TOKEN / 与 ADB_RELAY_TOKEN 相同
adbd host       127.0.0.1
adbd port       5555 or wireless debugging port / 5555 或无线调试端口
```

中文：然后点击 `Start relay`。

English: Then tap `Start relay`.

## 从 VPS 或 code-server 连接 / Connect From VPS or code-server

安装 ADB / Install ADB:

```bash
apt update
apt install -y android-tools-adb
adb version
```

连接手机 / Connect to the phone:

```bash
adb connect 127.0.0.1:40001
adb devices -l
adb -s 127.0.0.1:40001 shell getprop ro.product.model
```

## 多设备模式 / Multi-Device Mode

中文：每台手机需要一个稳定的 `Device id` 和一个独立的 ADB 端口。

English: Each phone needs a stable `Device id` and a dedicated ADB port.

`.env` 示例 / Example `.env`:

```env
ADB_RELAY_TOKEN=replace-with-a-long-random-token
ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000
ADB_RELAY_DEVICES=phone-1:0.0.0.0:40001,phone-2:0.0.0.0:40002
DEVICE_RELAY_PORT=7000
```

在 `docker-compose.yml` 中增加第二个 ADB 端口 / Add the second ADB port to `docker-compose.yml`:

```yaml
ports:
  - "7000:7000"
  - "127.0.0.1:40001:40001"
  - "127.0.0.1:40002:40002"
```

启动服务 / Start the service:

```bash
docker compose up -d --build
```

从 VPS 连接 / Connect from the VPS:

```bash
adb connect 127.0.0.1:40001
adb connect 127.0.0.1:40002
adb devices -l
```

中文：Android App 里的 `Device id` 必须分别匹配 `phone-1` 和 `phone-2`。

English: The Android app `Device id` values must match `phone-1` and `phone-2`.

## 不使用 Docker 运行 / Run Without Docker

```bash
cd server
npm test
node src/cli.js \
  --token 'replace-with-a-long-random-token' \
  --device-listen 0.0.0.0:7000 \
  --adb-listen 127.0.0.1:40001
```

多设备模式 / Multi-device mode:

```bash
node src/cli.js \
  --token 'replace-with-a-long-random-token' \
  --device-listen 0.0.0.0:7000 \
  --device phone-1:127.0.0.1:40001 \
  --device phone-2:127.0.0.1:40002
```

## 环境变量 / Environment Variables

中文：Docker 镜像和 CLI 都支持以下变量。

English: Both the Docker image and CLI support these variables.

```text
ADB_RELAY_TOKEN          Required shared token / 必填共享密钥
ADB_RELAY_DEVICE_LISTEN  Android-agent listener, default 0.0.0.0:7000 / Android Agent 入口
ADB_RELAY_ADB_LISTEN     Single-device ADB listener, default 127.0.0.1:40001 / 单设备 ADB 入口
ADB_RELAY_DEVICES        Comma-separated multi-device mappings / 逗号分隔的多设备映射
```

中文：如果设置了 `ADB_RELAY_DEVICES`，服务会进入多设备模式，并忽略 `ADB_RELAY_ADB_LISTEN`。

English: If `ADB_RELAY_DEVICES` is set, the server uses multi-device mode and ignores `ADB_RELAY_ADB_LISTEN`.

## GitHub Container Registry

中文：`.github/workflows/docker-image.yml` 会在 pull request 上构建镜像，并在 `main` 和 `v*` tag 上推送镜像到 GHCR。

English: `.github/workflows/docker-image.yml` builds the image for pull requests and pushes images to GHCR on `main` and `v*` tags.

```text
ghcr.io/endlessjy/adb-relay:latest
ghcr.io/endlessjy/adb-relay:main
ghcr.io/endlessjy/adb-relay:vX.Y.Z
```

使用已发布镜像 / Use the published image:

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

## 排障 / Troubleshooting

查看容器日志 / Check container logs:

```bash
docker compose logs -f adb-relay
```

确认监听端口 / Confirm listeners:

```bash
ss -ltnp | grep -E '7000|40001'
```

如果 `adb connect` 成功但命令失败，请检查 / If `adb connect` succeeds but commands fail, check:

- Android agent notification status / Android Agent 通知状态
- phone wireless debugging port / 手机无线调试端口
- `ADB_RELAY_TOKEN` matches the app Token field / `ADB_RELAY_TOKEN` 与 App Token 字段一致
- `Device id` matches the mapped device id in multi-device mode / 多设备模式下 `Device id` 与映射一致
- VPS firewall exposes `7000/tcp` / VPS 防火墙已开放 `7000/tcp`
- ADB port is not exposed publicly / ADB 端口没有暴露到公网
