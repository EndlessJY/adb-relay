import assert from "node:assert/strict";
import test from "node:test";

import { buildConfigFromArgs } from "../src/cli-config.js";

test("buildConfigFromArgs builds relay server config from explicit endpoints", () => {
  const config = buildConfigFromArgs([
    "--token",
    "secret",
    "--device-listen",
    "0.0.0.0:7000",
    "--adb-listen",
    "127.0.0.1:40001"
  ]);

  assert.deepEqual(config, {
    token: "secret",
    deviceListen: { host: "0.0.0.0", port: 7000 },
    adbListen: { host: "127.0.0.1", port: 40001 }
  });
});

test("buildConfigFromArgs requires a token", () => {
  assert.throws(
    () => buildConfigFromArgs([]),
    /Missing required --token or ADB_RELAY_TOKEN/
  );
});

test("buildConfigFromArgs supports repeated explicit device port mappings", () => {
  const config = buildConfigFromArgs([
    "--token",
    "secret",
    "--device",
    "phone-1:127.0.0.1:40001",
    "--device",
    "phone-2:127.0.0.1:40002"
  ]);

  assert.deepEqual(config.adbMappings, [
    { deviceId: "phone-1", listen: { host: "127.0.0.1", port: 40001 } },
    { deviceId: "phone-2", listen: { host: "127.0.0.1", port: 40002 } }
  ]);
});

test("buildConfigFromArgs reads docker-friendly environment variables", () => {
  process.env.ADB_RELAY_TOKEN = "env-secret";
  process.env.ADB_RELAY_DEVICE_LISTEN = "0.0.0.0:7000";
  process.env.ADB_RELAY_ADB_LISTEN = "127.0.0.1:40001";

  try {
    const config = buildConfigFromArgs([]);

    assert.deepEqual(config, {
      token: "env-secret",
      deviceListen: { host: "0.0.0.0", port: 7000 },
      adbListen: { host: "127.0.0.1", port: 40001 }
    });
  } finally {
    delete process.env.ADB_RELAY_TOKEN;
    delete process.env.ADB_RELAY_DEVICE_LISTEN;
    delete process.env.ADB_RELAY_ADB_LISTEN;
  }
});

test("buildConfigFromArgs reads mapped devices from environment variables", () => {
  process.env.ADB_RELAY_TOKEN = "env-secret";
  process.env.ADB_RELAY_DEVICES = "phone-1:127.0.0.1:40001,phone-2:127.0.0.1:40002";

  try {
    const config = buildConfigFromArgs([]);

    assert.deepEqual(config.adbMappings, [
      { deviceId: "phone-1", listen: { host: "127.0.0.1", port: 40001 } },
      { deviceId: "phone-2", listen: { host: "127.0.0.1", port: 40002 } }
    ]);
    assert.equal(config.adbListen, undefined);
  } finally {
    delete process.env.ADB_RELAY_TOKEN;
    delete process.env.ADB_RELAY_DEVICES;
  }
});
