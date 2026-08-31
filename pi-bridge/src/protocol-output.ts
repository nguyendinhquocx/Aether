import {
  takeOverStdout,
  writeRawStdout,
} from "../node_modules/@earendil-works/pi-coding-agent/dist/core/output-guard.js";

export function reserveProtocolStdout(): void {
  takeOverStdout();
}

export function writeProtocolFrame(frame: Record<string, unknown>): void {
  writeRawStdout(`${JSON.stringify(frame)}\n`);
}
