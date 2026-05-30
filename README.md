# ADB Relay

ADB Relay 是一个轻量 TCP 反向代理，用来把 Android 手机上真实运行的 `adbd` 暴露给 VPS 或 code-server 环境使用。

它不实现 ADB 协议，只转发 TCP 字节流。真正执行 `shell`、`install`、`logcat`、`push`、`pull` 的仍然是手机系统里的 `adbd`。

```text
code-server / VPS
  adb connect 127.0.0.1:40001
        |
        v
Docker 容器: adb-relay-server
  0.0.0.0:7000   Android Agent 连接到这里
  0.0.0.0:40001  ADB 客户端通过宿主机本地端口连接到这里
        |
        v
Android Agent App
  连接手机本机 adbd
```

## 项目结构

```text
server/         Node.js 中继服务端
android-agent/ Android 前台服务 App
Dockerfile      服务端容器镜像
docker-compose.yml
```

## 安全模型

所有 ADB 入口都要按高风险端口处理。任何能访问 ADB 监听端口的人，都可能通过 `adb` 操作你的手机。

推荐默认配置：

- 只把 `7000/tcp` 开放给需要运行 Android Agent 的设备。
- ADB 端口保持在宿主机本地回环，例如 Docker 映射 `127.0.0.1:40001:40001`。
- 使用足够长、随机的 `ADB_RELAY_TOKEN`。
- 尽量放在 WireGuard、Tailscale、SSH tunnel 或防火墙之后。

当前握手使用明文 TCP 上的共享 token，不要把它当作公开零信任服务。

## Docker 部署

### 1. 创建 `.env`

```bash
cp .env.example .env
```

编辑 `.env`：

```env
ADB_RELAY_TOKEN=replace-with-a-long-random-token
ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000
ADB_RELAY_DEVICES=phone-1:0.0.0.0:40001,phone-2:0.0.0.0:40002
DEVICE_RELAY_PORT=7000
ADB_RELAY_PORT_1=40001
ADB_RELAY_PORT_2=40002
```

默认配置是多设备模式，`phone-1` 对应 `127.0.0.1:40001`，`phone-2` 对应 `127.0.0.1:40002`。

在 Docker 容器内，服务端应监听 `0.0.0.0`。Compose 文件已经把宿主机侧 ADB 端口限制到 `127.0.0.1`。

### 2. 从 GitHub 拉取镜像并启动

```bash
docker compose pull
docker compose up -d
docker compose logs -f adb-relay
```

预期日志：

```text
[listen] device relay on 0.0.0.0:7000
[listen] adb local port for phone-1 on 0.0.0.0:40001
[listen] adb local port for phone-2 on 0.0.0.0:40002
```

### 3. 开放防火墙

VPS 上只需要开放 Android Agent 连接的端口。

```bash
ufw allow 7000/tcp
```

不要把 `40001/tcp` 公开到公网。`docker-compose.yml` 默认这样发布：

```yaml
ports:
  - "7000:7000"
  - "127.0.0.1:40001:40001"
  - "127.0.0.1:40002:40002"
```

这表示 VPS 本机的 code-server 或 shell 可以连接它，但公网不能直接访问。

## Android Agent

每次推送到 `main` 或创建 `v*` tag 时，GitHub Actions 会自动编译 Android APK。

- 推送到 `main`：APK 会上传到 `android-apk-latest` 这个 GitHub Release。
- 创建 `v*` tag：APK 会上传到对应版本的 GitHub Release。

也可以在本地构建并安装 Android App：

```bash
cd android-agent
./build.sh
adb install -r adb-relay-agent-debug.apk
```

构建脚本直接使用 Android SDK command-line tools。如果 SDK 不在默认位置：

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

打开 App 并填写：

```text
VPS host        VPS 公网 IP 或域名
VPS relay port  7000
Device id       phone-1
Token           与 ADB_RELAY_TOKEN 相同
adbd host       127.0.0.1
adbd port       5555 或无线调试端口
```

然后点击 `Start relay`。

## 从 VPS 或 code-server 连接

安装 ADB：

```bash
apt update
apt install -y android-tools-adb
adb version
```

连接手机：

```bash
adb connect 127.0.0.1:40001
adb devices -l
adb -s 127.0.0.1:40001 shell getprop ro.product.model
```

## 多设备模式

每台手机需要一个稳定的 `Device id` 和一个独立的 ADB 端口。

`.env.example` 默认已经是双设备模式：

```env
ADB_RELAY_TOKEN=replace-with-a-long-random-token
ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000
ADB_RELAY_DEVICES=phone-1:0.0.0.0:40001,phone-2:0.0.0.0:40002
DEVICE_RELAY_PORT=7000
ADB_RELAY_PORT_1=40001
ADB_RELAY_PORT_2=40002
```

`docker-compose.yml` 默认发布两个 ADB 端口：

```yaml
ports:
  - "7000:7000"
  - "127.0.0.1:40001:40001"
  - "127.0.0.1:40002:40002"
```

启动服务：

```bash
docker compose pull
docker compose up -d
```

从 VPS 连接：

```bash
adb connect 127.0.0.1:40001
adb connect 127.0.0.1:40002
adb devices -l
```

Android App 里的 `Device id` 必须分别匹配 `phone-1` 和 `phone-2`。

## 不使用 Docker 运行

```bash
cd server
npm test
node src/cli.js \
  --token 'replace-with-a-long-random-token' \
  --device-listen 0.0.0.0:7000 \
  --adb-listen 127.0.0.1:40001
```

多设备模式：

```bash
node src/cli.js \
  --token 'replace-with-a-long-random-token' \
  --device-listen 0.0.0.0:7000 \
  --device phone-1:127.0.0.1:40001 \
  --device phone-2:127.0.0.1:40002
```

## 环境变量

Docker 镜像和 CLI 都支持以下变量：

```text
ADB_RELAY_TOKEN          必填共享密钥
ADB_RELAY_DEVICE_LISTEN  Android Agent 入口，默认 0.0.0.0:7000
ADB_RELAY_ADB_LISTEN     单设备 ADB 入口，默认 127.0.0.1:40001
ADB_RELAY_DEVICES        逗号分隔的多设备映射
```

如果设置了 `ADB_RELAY_DEVICES`，服务会进入多设备模式，并忽略 `ADB_RELAY_ADB_LISTEN`。

## GitHub Container Registry

`.github/workflows/docker-image.yml` 会在 pull request 上构建镜像，并在 `main` 和 `v*` tag 上推送镜像到 GHCR。

同一个 workflow 还会自动编译 Android Agent APK。`main` 分支的最新 APK 会发布到 `android-apk-latest` release，版本 tag 的 APK 会发布到对应版本 release。

```text
ghcr.io/endlessjy/adb-relay:latest
ghcr.io/endlessjy/adb-relay:main
ghcr.io/endlessjy/adb-relay:vX.Y.Z
```

使用已发布镜像：

```bash
docker run -d \
  --name adb-relay \
  --restart unless-stopped \
  -e ADB_RELAY_TOKEN='replace-with-a-long-random-token' \
  -e ADB_RELAY_DEVICE_LISTEN='0.0.0.0:7000' \
  -e ADB_RELAY_DEVICES='phone-1:0.0.0.0:40001,phone-2:0.0.0.0:40002' \
  -p 7000:7000 \
  -p 127.0.0.1:40001:40001 \
  -p 127.0.0.1:40002:40002 \
  ghcr.io/endlessjy/adb-relay:latest
```

## 排障

查看容器日志：

```bash
docker compose logs -f adb-relay
```

确认监听端口：

```bash
ss -ltnp | grep -E '7000|40001|40002'
```

如果 `adb connect` 成功但命令失败，请检查：

- Android Agent 通知状态。
- 手机无线调试端口。
- `ADB_RELAY_TOKEN` 与 App Token 字段一致。
- 多设备模式下 `Device id` 与映射一致。
- VPS 防火墙已开放 `7000/tcp`。
- ADB 端口没有暴露到公网。
