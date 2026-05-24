import { parseEndpoint } from "./relay-server.js";

const DEFAULT_DEVICE_LISTEN = "0.0.0.0:7000";
const DEFAULT_ADB_LISTEN = "127.0.0.1:40001";

function env(name) {
  const value = process.env[name];
  return value && value.trim().length > 0 ? value.trim() : undefined;
}

function readOption(args, name, fallback) {
  const index = args.indexOf(name);
  if (index === -1) return fallback;
  const value = args[index + 1];
  if (!value || value.startsWith("--")) {
    throw new Error(`Missing value for ${name}.`);
  }
  return value;
}

function readRepeatedOptions(args, name) {
  const values = [];
  for (let index = 0; index < args.length; index++) {
    if (args[index] !== name) continue;
    const value = args[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for ${name}.`);
    }
    values.push(value);
    index++;
  }
  return values;
}

function parseDeviceMapping(value) {
  const separator = value.indexOf(":");
  if (separator <= 0 || separator === value.length - 1) {
    throw new Error(`Invalid device mapping "${value}". Expected deviceId:host:port.`);
  }
  const deviceId = value.slice(0, separator);
  if (/\s/.test(deviceId)) {
    throw new Error(`Invalid device id "${deviceId}". Device ids cannot contain whitespace.`);
  }
  return {
    deviceId,
    listen: parseEndpoint(value.slice(separator + 1))
  };
}

export function buildConfigFromArgs(args) {
  const token = readOption(args, "--token", env("ADB_RELAY_TOKEN"));
  if (!token) {
    throw new Error("Missing required --token or ADB_RELAY_TOKEN.");
  }

  const deviceValues = readRepeatedOptions(args, "--device");
  if (deviceValues.length === 0 && env("ADB_RELAY_DEVICES")) {
    deviceValues.push(...env("ADB_RELAY_DEVICES").split(",").map((value) => value.trim()).filter(Boolean));
  }

  const adbMappings = deviceValues.map(parseDeviceMapping);
  const config = {
    token,
    deviceListen: parseEndpoint(readOption(args, "--device-listen", env("ADB_RELAY_DEVICE_LISTEN") ?? DEFAULT_DEVICE_LISTEN))
  };
  if (adbMappings.length > 0) {
    config.adbMappings = adbMappings;
  } else {
    config.adbListen = parseEndpoint(readOption(args, "--adb-listen", env("ADB_RELAY_ADB_LISTEN") ?? DEFAULT_ADB_LISTEN));
  }
  return config;
}

export function usage() {
  return [
    "Usage:",
    "  adb-relay-server --token <secret> [--device-listen 0.0.0.0:7000] [--adb-listen 127.0.0.1:40001]",
    "  adb-relay-server --token <secret> --device phone-1:127.0.0.1:40001 --device phone-2:127.0.0.1:40002",
    "",
    "Environment variables:",
    "  ADB_RELAY_TOKEN=<secret>",
    "  ADB_RELAY_DEVICE_LISTEN=0.0.0.0:7000",
    "  ADB_RELAY_ADB_LISTEN=127.0.0.1:40001",
    "  ADB_RELAY_DEVICES=phone-1:127.0.0.1:40001,phone-2:127.0.0.1:40002",
    "",
    "Use repeated --device mappings for multi-device mode. Device ids must match the Android app Device id field.",
    "The adb listener should stay bound to 127.0.0.1 unless you put another trusted tunnel in front of it."
  ].join("\n");
}
