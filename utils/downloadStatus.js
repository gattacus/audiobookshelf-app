export function storageFailureMessage(part, getString, bytesPretty) {
  const toFree = Math.max(0, Number(part.requiredStorageBytes) - Number(part.availableStorageBytes))
  return getString('MessageDownloadStoppedStorage', [bytesPretty(toFree)])
}
