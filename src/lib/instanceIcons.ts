// Instance icons are Minecraft item and block textures. Nothing from the game is bundled: the
// backend reads them at run time from a client jar downloaded from Mojang (`game::textures`)
// and serves them as `localimg://…/mc/<texture>`. Icon values are `mc:<texture>`.

export const MC_ICONS: string[] = [
  "mc:block/grass_block_side",
  "mc:item/diamond",
  "mc:item/emerald",
  "mc:item/diamond_sword",
  "mc:item/diamond_pickaxe",
  "mc:item/netherite_sword",
  "mc:item/bow",
  "mc:item/trident",
  "mc:item/golden_apple",
  "mc:item/apple",
  "mc:item/cookie",
  "mc:item/cake",
  "mc:item/ender_pearl",
  "mc:item/ender_eye",
  "mc:item/nether_star",
  "mc:item/totem_of_undying",
  "mc:item/blaze_powder",
  "mc:item/experience_bottle",
  "mc:item/enchanted_book",
  "mc:item/name_tag",
  "mc:item/elytra",
  "mc:item/fishing_rod",
  "mc:item/redstone",
  "mc:item/slime_ball",
  "mc:item/heart_of_the_sea",
  "mc:item/music_disc_cat",
  "mc:item/spyglass",
  "mc:item/map",
  "mc:item/gold_ingot",
  "mc:item/iron_ingot",
  "mc:item/netherite_ingot",
  "mc:block/crafting_table_front",
  "mc:block/tnt_side",
  "mc:block/furnace_front",
  "mc:block/oak_log",
  "mc:block/oak_planks",
  "mc:block/stone",
  "mc:block/cobblestone",
  "mc:block/diamond_ore",
  "mc:block/bookshelf",
  "mc:block/glowstone",
  "mc:block/obsidian",
  "mc:block/netherrack",
  "mc:block/end_stone",
  "mc:block/sand",
  "mc:block/ice",
  "mc:block/jack_o_lantern",
  "mc:block/beacon",
  "mc:block/bedrock",
  "mc:block/gold_block",
  "mc:block/diamond_block",
  "mc:block/emerald_block",
];

/** Built-in icons of earlier versions (original pixel art) mapped to similar textures. */
const LEGACY: Record<string, string> = {
  grass: "mc:block/grass_block_side",
  diamond: "mc:item/diamond",
  emerald: "mc:item/emerald",
  sword: "mc:item/diamond_sword",
  pickaxe: "mc:item/diamond_pickaxe",
  heart: "mc:item/golden_apple",
  star: "mc:item/nether_star",
  flame: "mc:item/blaze_powder",
  potion: "mc:item/experience_bottle",
  tree: "mc:block/oak_log",
  chest: "mc:block/crafting_table_front",
  book: "mc:item/enchanted_book",
  shield: "mc:item/totem_of_undying",
  gear: "mc:block/furnace_front",
  moon: "mc:item/ender_eye",
  apple: "mc:item/apple",
};

/** The texture (`item/diamond`) of an icon value, or null for the default and file icons. */
export function iconTexture(icon: string | null | undefined): string | null {
  if (!icon) return null;
  const key = LEGACY[icon] ?? icon;
  return key.startsWith("mc:") ? key.slice(3) : null;
}

/** i18n key of an icon's name (`iconPicker.icons.diamond_sword`). */
export function iconLabelKey(icon: string): string {
  return `iconPicker.icons.${icon.slice(icon.lastIndexOf("/") + 1)}`;
}
