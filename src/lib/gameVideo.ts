// The game's menus can't play videos, so a video background is turned into frames for them: the
// window plays the video hidden, grabs pictures at even steps and the launcher saves them next to
// the mod (see begin/save/finish_video_frames). Once per video; later the frames are reused.
import { commands } from "./api";

const FPS = 8;
const MAX_FRAMES = 160;
const WIDTH = 512;

const running = new Map<string, Promise<boolean>>();

function once(target: EventTarget, name: string, ms = 15000): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`no ${name}`)), ms);
    target.addEventListener(name, () => (clearTimeout(timer), resolve()), { once: true });
    target.addEventListener("error", () => (clearTimeout(timer), reject(new Error("video error"))), {
      once: true,
    });
  });
}

function base64(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

async function make(video: string, url: string): Promise<boolean> {
  if (await commands.videoFramesReady(video)) return true;
  const v = document.createElement("video");
  v.crossOrigin = "anonymous";
  v.muted = true;
  v.preload = "auto";
  v.src = url;
  try {
    await once(v, "loadeddata");
    const span = Math.min(v.duration || 0, MAX_FRAMES / FPS);
    if (!(span > 0)) return false;
    const count = Math.max(1, Math.round(span * FPS));
    const height = Math.max(1, Math.round((v.videoHeight / v.videoWidth) * WIDTH));
    const canvas = document.createElement("canvas");
    canvas.width = WIDTH;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return false;
    await commands.beginVideoFrames(video);
    for (let i = 0; i < count; i++) {
      v.currentTime = (i * span) / count;
      await once(v, "seeked");
      ctx.drawImage(v, 0, 0, WIDTH, height);
      const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, "image/jpeg", 0.82));
      if (!blob) return false;
      await commands.saveVideoFrame(video, i, base64(new Uint8Array(await blob.arrayBuffer())));
    }
    await commands.finishVideoFrames(video, Math.round((span * 1000) / count), count);
    return true;
  } catch (e) {
    console.warn("game frames of the video background not made", e);
    return false;
  } finally {
    v.removeAttribute("src");
    v.load();
  }
}

/** The frames' asset name for the game (`<video>.frames`) once they exist, else null. */
export async function gameFrames(video: string, url: string): Promise<string | null> {
  let job = running.get(video);
  if (!job) {
    job = make(video, url);
    running.set(video, job);
    job.then((ok) => ok || running.delete(video));
  }
  return (await job) ? `${video}.frames` : null;
}
