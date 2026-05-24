import assert from "node:assert/strict";
import net from "node:net";
import test from "node:test";
import { once } from "node:events";

import { RelayServer, parseEndpoint } from "../src/relay-server.js";

function connect(port, host = "127.0.0.1") {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host, port });
    socket.once("connect", () => resolve(socket));
    socket.once("error", reject);
  });
}

function readOnce(socket) {
  return new Promise((resolve) => {
    socket.once("data", (chunk) => resolve(chunk.toString("utf8")));
  });
}

test("parseEndpoint parses host and port", () => {
  assert.deepEqual(parseEndpoint("127.0.0.1:40001"), {
    host: "127.0.0.1",
    port: 40001
  });
});

test("authenticated device is piped to the VPS-local adb socket", async (t) => {
  const server = new RelayServer({
    token: "secret",
    deviceListen: { host: "127.0.0.1", port: 0 },
    adbListen: { host: "127.0.0.1", port: 0 }
  });
  t.after(() => server.stop());

  await server.start();
  const { devicePort, adbPort } = server.addresses();

  const device = await connect(devicePort);
  t.after(() => device.destroy());
  device.write("ADBRELAY/1 phone-1 secret\n");
  await once(server, "deviceReady");

  const adb = await connect(adbPort);
  t.after(() => adb.destroy());

  adb.write("hello-adbd");
  assert.equal(await readOnce(device), "hello-adbd");

  device.write("hello-adb");
  assert.equal(await readOnce(adb), "hello-adb");
});

test("mapped adb ports are piped to their matching device ids", async (t) => {
  const server = new RelayServer({
    token: "secret",
    deviceListen: { host: "127.0.0.1", port: 0 },
    adbMappings: [
      { deviceId: "phone-1", listen: { host: "127.0.0.1", port: 0 } },
      { deviceId: "phone-2", listen: { host: "127.0.0.1", port: 0 } }
    ]
  });
  t.after(() => server.stop());

  await server.start();
  const { devicePort, adbMappings } = server.addresses();

  const phone1 = await connect(devicePort);
  const phone2 = await connect(devicePort);
  t.after(() => phone1.destroy());
  t.after(() => phone2.destroy());
  const phone1Ready = once(server, "deviceReady");
  phone1.write("ADBRELAY/1 phone-1 secret\n");
  assert.equal(await phone1Ready.then(([id]) => id), "phone-1");
  const phone2Ready = once(server, "deviceReady");
  phone2.write("ADBRELAY/1 phone-2 secret\n");
  assert.equal(await phone2Ready.then(([id]) => id), "phone-2");

  const adb1 = await connect(adbMappings.get("phone-1").port);
  const adb2 = await connect(adbMappings.get("phone-2").port);
  t.after(() => adb1.destroy());
  t.after(() => adb2.destroy());

  adb1.write("to-phone-1");
  adb2.write("to-phone-2");
  assert.equal(await readOnce(phone1), "to-phone-1");
  assert.equal(await readOnce(phone2), "to-phone-2");

  phone1.write("from-phone-1");
  phone2.write("from-phone-2");
  assert.equal(await readOnce(adb1), "from-phone-1");
  assert.equal(await readOnce(adb2), "from-phone-2");
});

test("device with a wrong token is rejected before it can serve adb clients", async (t) => {
  const server = new RelayServer({
    token: "secret",
    deviceListen: { host: "127.0.0.1", port: 0 },
    adbListen: { host: "127.0.0.1", port: 0 }
  });
  t.after(() => server.stop());

  await server.start();
  const { devicePort } = server.addresses();

  const device = await connect(devicePort);
  device.write("ADBRELAY/1 phone-1 wrong-token\n");
  await once(device, "close");

  assert.equal(server.deviceCount(), 0);
});
