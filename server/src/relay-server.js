import { EventEmitter } from "node:events";
import net from "node:net";

const HANDSHAKE_PREFIX = "ADBRELAY/1";

export function parseEndpoint(value) {
  const separator = value.lastIndexOf(":");
  if (separator <= 0 || separator === value.length - 1) {
    throw new Error(`Invalid endpoint "${value}". Expected host:port.`);
  }

  const host = value.slice(0, separator);
  const port = Number(value.slice(separator + 1));
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Invalid port in endpoint "${value}".`);
  }

  return { host, port };
}

function waitForListening(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.once("listening", () => {
      server.off("error", reject);
      resolve();
    });
  });
}

function closeServer(server) {
  return new Promise((resolve, reject) => {
    if (!server.listening) {
      resolve();
      return;
    }
    server.close((error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}

function socketAddressPort(server, label) {
  const address = server.address();
  if (!address || typeof address === "string") {
    throw new Error(`${label} listener is not bound to a TCP address.`);
  }
  return address.port;
}

export class RelayServer extends EventEmitter {
  constructor(options) {
    super();
    this.token = options.token;
    this.deviceListen = options.deviceListen;
    this.adbListen = options.adbListen ?? null;
    this.adbMappings = options.adbMappings ?? [];
    this.handshakeTimeoutMs = options.handshakeTimeoutMs ?? 5000;
    this.devices = new Map();
    this.deviceServer = net.createServer((socket) => this.#handleDevice(socket));
    this.adbServer = this.adbListen
      ? net.createServer((socket) => this.#handleAdbClient(socket))
      : null;
    this.mappedAdbServers = this.adbMappings.map((mapping) => ({
      deviceId: mapping.deviceId,
      listen: mapping.listen,
      server: net.createServer((socket) => this.#handleAdbClient(socket, mapping.deviceId))
    }));
  }

  async start() {
    this.deviceServer.listen(this.deviceListen.port, this.deviceListen.host);
    const waiters = [waitForListening(this.deviceServer)];
    if (this.adbServer) {
      this.adbServer.listen(this.adbListen.port, this.adbListen.host);
      waiters.push(waitForListening(this.adbServer));
    }
    for (const mapping of this.mappedAdbServers) {
      mapping.server.listen(mapping.listen.port, mapping.listen.host);
      waiters.push(waitForListening(mapping.server));
    }
    await Promise.all(waiters);
  }

  async stop() {
    for (const device of this.devices.values()) {
      device.socket.destroy();
    }
    this.devices.clear();
    const closers = [closeServer(this.deviceServer)];
    if (this.adbServer) closers.push(closeServer(this.adbServer));
    for (const mapping of this.mappedAdbServers) {
      closers.push(closeServer(mapping.server));
    }
    await Promise.all(closers);
  }

  addresses() {
    const result = {
      devicePort: socketAddressPort(this.deviceServer, "device"),
      adbMappings: new Map()
    };
    if (this.adbServer) {
      result.adbPort = socketAddressPort(this.adbServer, "adb");
    }
    for (const mapping of this.mappedAdbServers) {
      result.adbMappings.set(mapping.deviceId, {
        host: mapping.listen.host,
        port: socketAddressPort(mapping.server, `adb:${mapping.deviceId}`)
      });
    }
    return result;
  }

  deviceCount() {
    return this.devices.size;
  }

  #handleDevice(socket) {
    socket.setNoDelay(true);
    socket.on("error", (error) => this.emit("socketError", error));

    let buffer = Buffer.alloc(0);
    const timer = setTimeout(() => socket.destroy(), this.handshakeTimeoutMs);

    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const newline = buffer.indexOf(0x0a);
      if (newline === -1) {
        if (buffer.length > 1024) socket.destroy();
        return;
      }

      clearTimeout(timer);
      socket.off("data", onData);

      const line = buffer.subarray(0, newline).toString("utf8").trim();
      const rest = buffer.subarray(newline + 1);
      const [prefix, deviceId, token] = line.split(/\s+/);
      if (prefix !== HANDSHAKE_PREFIX || !deviceId || token !== this.token) {
        socket.destroy();
        return;
      }

      if (rest.length > 0) {
        socket.unshift(rest);
      }

      const previous = this.devices.get(deviceId);
      if (previous) {
        previous.socket.destroy();
      }

      const device = { id: deviceId, socket, busy: false };
      this.devices.set(deviceId, device);
      socket.pause();
      socket.once("close", () => {
        if (this.devices.get(deviceId) === device) {
          this.devices.delete(deviceId);
          this.emit("deviceGone", deviceId);
        }
      });
      this.emit("deviceReady", deviceId);
    };

    socket.on("data", onData);
    socket.once("close", () => clearTimeout(timer));
  }

  #handleAdbClient(client, deviceId = null) {
    client.setNoDelay(true);
    client.on("error", (error) => this.emit("socketError", error));

    const device = deviceId
      ? this.devices.get(deviceId)
      : [...this.devices.values()].find((entry) => !entry.busy);
    if (!device || device.busy) {
      client.destroy();
      return;
    }

    device.busy = true;
    device.socket.resume();
    client.pipe(device.socket);
    device.socket.pipe(client);

    const closeBoth = () => {
      client.destroy();
      device.socket.destroy();
    };
    client.once("close", closeBoth);
    device.socket.once("close", closeBoth);
    this.emit("sessionStart", device.id);
  }
}
