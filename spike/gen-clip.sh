#!/usr/bin/env bash
# Generate the test clips: 1920x1200 (Model 3 screen), 60 fps, 10 s,
# no B-frames, CBR with a one-frame buffer, one keyframe per loop. That
# matches what the phone encoder will produce. AUD NAL units mark frame
# boundaries; parameter sets are repeated before every keyframe.
#   clip.h264  H.264 High L5.0, 10 Mbit/s
#   clip.hevc  HEVC Main L5.0,   6 Mbit/s (same quality at ~40% less bitrate)
set -euo pipefail
cd "$(dirname "$0")"
SRC=(-f lavfi -i "testsrc2=size=1920x1200:rate=60" -t 10 -pix_fmt yuv420p)

ffmpeg -hide_banner -loglevel error -y "${SRC[@]}" \
  -c:v libx264 -preset veryfast -tune zerolatency -profile:v high -level 5.0 \
  -b:v 10M -maxrate 10M -bufsize 170k -g 600 -bf 0 \
  -x264-params "aud=1:repeat-headers=1:nal-hrd=cbr" \
  -f h264 clip.h264

ffmpeg -hide_banner -loglevel error -y "${SRC[@]}" \
  -c:v libx265 -preset veryfast -tune zerolatency -profile:v main \
  -g 600 \
  -x265-params "bitrate=6000:vbv-maxrate=6000:vbv-bufsize=200:rc-lookahead=0:aud=1:repeat-headers=1:bframes=0:keyint=600:min-keyint=600:level-idc=50:log-level=error" \
  -f hevc clip.hevc

ls -lh clip.h264 clip.hevc
