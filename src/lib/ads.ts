// Ad slots: the places in the interface where an ad may appear, and the single switch that decides
// what fills them. Nothing is shown today: the default provider returns no ads, and an empty slot
// renders nothing and takes no space.
//
// To start showing ads:
//  1. Write an `AdProvider` (a network's SDK, or your own JSON feed) and call `setAdProvider()` once
//     at start-up, e.g. in `main.ts`.
//  2. Images go through the `img://` proxy (`imgUrl`), so no CSP change is needed for them; a provider
//     that loads scripts or iframes needs its domains in `src-tauri/tauri.conf.json` → `app.security.csp`.
//  3. Keep every ad marked as one (`ads.label`), away from game content, and check Mojang's usage
//     guidelines and the network's terms before shipping. Do not send account data to a provider.
//  4. New places: add an id to `AdSlotId` and put `<AdSlot slot="…" />` where it should appear.

export type AdSlotId = "home";

export interface Ad {
  /** Stable id from the provider, for impression and click reports. */
  id: string;
  title: string;
  text?: string;
  /** http(s) image; shown through the image proxy. */
  image?: string;
  /** Opened in the browser on click; only http(s). */
  url: string;
  /** Who pays for it, shown next to the "Sponsored" label. */
  sponsor?: string;
}

export interface AdProvider {
  /** The ad for a slot, or null to leave it empty. Should not throw. */
  load(slot: AdSlotId): Promise<Ad | null>;
  /** The ad was visible on screen. */
  impression?(ad: Ad, slot: AdSlotId): void;
  click?(ad: Ad, slot: AdSlotId): void;
}

const none: AdProvider = { load: async () => null };
let provider: AdProvider = none;

export function setAdProvider(p: AdProvider | null): void {
  provider = p ?? none;
}

export function adProvider(): AdProvider {
  return provider;
}

/** The ad for `slot`, never throwing: a broken provider just means no ad. */
export async function loadAd(slot: AdSlotId): Promise<Ad | null> {
  try {
    const ad = await provider.load(slot);
    return ad && /^https?:\/\//i.test(ad.url) ? ad : null;
  } catch {
    return null;
  }
}
