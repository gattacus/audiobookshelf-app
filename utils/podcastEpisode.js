// Server episodes carry audioFile; downloaded episodes carry audioTrack instead.
export function episodeFilename(episode) {
  return episode.audioFile?.metadata?.filename || episode.audioTrack?.metadata?.filename || ''
}
