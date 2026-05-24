#!/usr/bin/env node
import { buildConfigFromArgs, usage } from "./cli-config.js";
import { RelayServer } from "./relay-server.js";

async function main() {
  if (process.argv.includes("--help") || process.argv.includes("-h")) {
    console.log(usage());
    return;
  }

  const config = buildConfigFromArgs(process.argv.slice(2));
  const server = new RelayServer(config);

  server.on("deviceReady", (deviceId) => {
    console.log(`[device] ${deviceId} connected`);
  });
  server.on("deviceGone", (deviceId) => {
    console.log(`[device] ${deviceId} disconnected`);
  });
  server.on("sessionStart", (deviceId) => {
    console.log(`[adb] session started for ${deviceId}`);
  });
  server.on("socketError", (error) => {
    console.error(`[socket] ${error.message}`);
  });

  await server.start();
  const { devicePort, adbPort, adbMappings } = server.addresses();
  console.log(`[listen] device relay on ${config.deviceListen.host}:${devicePort}`);
  if (adbPort) {
    console.log(`[listen] adb local port on ${config.adbListen.host}:${adbPort}`);
  }
  for (const [deviceId, listen] of adbMappings) {
    console.log(`[listen] adb local port for ${deviceId} on ${listen.host}:${listen.port}`);
  }

  const stop = async () => {
    await server.stop();
    process.exit(0);
  };
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
}

main().catch((error) => {
  console.error(error.message);
  console.error("");
  console.error(usage());
  process.exit(1);
});
