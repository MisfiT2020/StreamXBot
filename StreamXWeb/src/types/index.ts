export interface Song {
  _id: string
  title: string
  artist: string
  album: string | null
  duration_sec: number
  cover_url: string | null
  spotify_url: string | null
  spotify?: TrackSpotifyInfo
  source_chat_id: number
  source_message_id: number
  type: string
  sampling_rate_hz: number
  updated_at: number
}

export interface Playlist {
  playlist_id: string
  name: string
  thumbnails?: string[]
  created_at: number
  updated_at: number
}

export interface BrowseResponse {
  page: number
  per_page: number
  total: number
  items: Song[]
}

export interface PlaylistsResponse {
  items: Playlist[]
}

export interface AvailablePlaylist {
  id: string
  kind: 'daily' | 'me_top_played'
  name: string
  thumbnail_url: string
  endpoint: string
  requires_auth: boolean
}

export interface AvailablePlaylistsResponse {
  items: AvailablePlaylist[]
}

export interface AvailablePlaylistTracksResponse {
  page: number
  per_page: number
  total: number
  items: Song[]
  cover_url?: string | null
}

export interface PlaylistTrackTelegramInfo {
  file_id?: string
  mime_type?: string
  file_size?: number
}

export interface PlaylistTrack {
  _id: string
  source_chat_id: number
  source_message_id: number
  telegram?: PlaylistTrackTelegramInfo
  audio?: TrackAudioInfo
  spotify?: { cover_url?: string; cover_source?: string; url?: string }
  updated_at: number
}

export interface PlaylistTracksResponse {
  page: number
  per_page: number
  total: number
  items: PlaylistTrack[]
}

export interface TrackAudioInfo {
  album?: string
  artist?: string
  year?: number
  duration_sec?: number
  type?: string
  bit_depth?: number
  bitrate_kbps?: number
  sampling_rate_hz?: number
  title?: string
}

export interface TrackSpotifyInfo {
  track_id?: string
  url?: string
  cover_url?: string
}

export interface TrackDetailsResponse {
  _id: string
  audio?: TrackAudioInfo
  spotify?: TrackSpotifyInfo
}

export interface TrackLyricsResponse {
  ok: boolean
  track_id: string
  url?: string
  lyrics?: string
}
