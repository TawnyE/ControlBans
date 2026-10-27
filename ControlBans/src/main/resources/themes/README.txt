===============================================================================
 ControlBans Theme Packs
===============================================================================

Theme packs are community-style recreations of the punishment screen look of
other well-known servers. They are ONLY cosmetic: a theme overrides the text
and colors of the messages players see when they are punished (ban screens,
kick screen, mute/warn messages) plus the chat prefix.

Available themes:
  hypixel.yml   - Hypixel-style tempban screen
  minemen.yml   - MineMen-style bold caps ban screen
  mcpvp.yml     - MCPVP-style compact ban screen

-------------------------------------------------------------------------------
 HOW TO INSTALL A THEME
-------------------------------------------------------------------------------

1. Open the theme file you want (e.g. hypixel.yml) from this folder.
2. Open plugins/ControlBans/locales/en.yml (or whichever locale file you use).
3. Copy the keys from the theme file into that locale file, REPLACING the keys
   that already exist there (prefix, screens.ban, screens.tempban, ...).
   Do not paste them as duplicates; YAML only honors one value per key.
4. Run "/cb reload" in game/console, or restart the server.

To go back to default, reinstall the jar's built-in locale or restore the
original keys the same way.

-------------------------------------------------------------------------------
 NOTES
-------------------------------------------------------------------------------

* These packs only restyle keys that already exist in en.yml. The stock
  screens do NOT include a separate "Ban ID" id-only line in Hypixel pixel-art
  position, nor a date line inside the MCPVP layout beyond what exists; themes
  reuse the existing placeholders (<player>, <staff>, <reason>, <duration>,
  <date>, <id>, <appeal>) and may drop lines a key provides.
* The <appeal> placeholder resolves from config.yml -> appeals.appeal-link.
  Set your own appeal URL there; theme files use the placeholder and never
  link to the real servers' appeal pages.
* All values are MiniMessage. Quotes must stay, comments in the theme files
  explain the tags.
* Theme packs are unofficial recreations of a general aesthetic and are not
  affiliated with Hypixel, MineMen, or MCPVP.
