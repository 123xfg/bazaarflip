# BazaarFlip

A Fabric mod for Minecraft 1.21.11 that finds profitable Hypixel Skyblock
Bazaar flips using Hypixel's **public** Bazaar API (no API key required), and
shows them as a **live overlay panel drawn directly on the Bazaar GUI** -
SkyCofl-style - so you never have to leave the menu to see what's worth
flipping right now.

## What it does

- Pulls a fresh Bazaar snapshot every ~30 seconds in the background.
- Whenever any screen titled "Bazaar" (or "Co-op Bazaar Orders", any Bazaar
  submenu) is open, a small panel is drawn near the top-right of the window
  listing the current best flips, ranked by **estimated profit/hour**
  (margin weighted by how fast the item actually trades, not just raw
  margin) - see `BazaarProduct.getEstimatedProfitPerHour()`.
- The panel updates itself automatically every refresh cycle - if a better
  flip appears while you're standing in the Bazaar menu, it just shows up,
  no button to press.
- **Press `B`** (rebindable in Controls) or type `/bazaarflip` to toggle the
  overlay on/off.
- `/bazaarflip chat [count] [margin|percent|hourly]` — plain-text version in
  chat, kept as a fallback / for sharing a list with co-op members.
- Filters out illiquid items (low weekly volume) and absurd margin %
  (usually stale data) so the list stays realistic. Defaults can be tuned in
  `BazaarFlipClient.java` (`MIN_WEEKLY_VOLUME`, `MAX_MARGIN_PERCENT`).

## How "flipping" works here

For each item, Hypixel exposes:
- `buyPrice` — what you pay to **instant-buy** (fills against the lowest sell order)
- `sellPrice` — what you get to **instant-sell** (fills against the highest buy order)

The flip strategy: place a **buy order** at (or near) `sellPrice`, wait for it
to fill, then place a **sell order** at (or near) `buyPrice`. The margin shown
is `buyPrice - sellPrice`, i.e. the spread you can capture. The overlay ranks
by *estimated profit/hour* = margin × (weekly volume / (7×24)), using
whichever side of the book (buy or sell) trades slower, since that's the real
bottleneck on how fast you'd actually get filled both ways. This mod does not
place orders for you — placing/managing orders automatically would violate
Hypixel's rules against auto-botting, so it stays informational: it tells you
*what* to flip, you place the orders yourself in-game.

## Requirements

- Java 21 to *build* the project (Fabric Loom 1.14 needs it to run the
  Gradle build itself); the compiled mod still only requires Java 17+ to
  play, since `sourceCompatibility`/`targetCompatibility` below are set to 17
- Minecraft 1.21.11 (the last Yarn-mapped release - see the note in
  `gradle.properties`)
- Fabric Loader 0.18+
- Fabric API (must be installed in your mods folder alongside this mod)

## Building

1. Install [Gradle](https://gradle.org/install/) if you don't already have it,
   or use an IDE with Gradle support (IntelliJ IDEA works well).
2. From the project root, generate the wrapper (one-time):
   ```
   gradle wrapper --gradle-version 8.7
   ```
3. Build the mod:
   ```
   ./gradlew build
   ```
   (or `gradlew.bat build` on Windows)
4. The compiled jar will be in `build/libs/bazaarflip-1.1.0.jar`.
5. Drop that jar, plus the matching **Fabric API** jar, into your
   `.minecraft/mods` folder.

## ⚠️ Heads up: the overlay is unverified - you'll likely need to adjust it

This was written in a sandbox with no access to Minecraft/Fabric's Maven
repos, so `BazaarOverlay.java` has not been test-compiled. It deliberately
avoids the riskiest thing (reading the vanilla GUI's exact pixel position,
which needs a mixin accessor and differs more across versions than anything
else) by just anchoring to a fixed margin from the window's edge instead -
but two things are still worth double-checking once you can build:

- **`ScreenEvents.afterRender`'s callback signature** - confirmed against
  Fabric API's own 1.21 javadoc to be
  `(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta)`,
  which is what the code uses, but re-check against your exact
  `fabric_version` if the build complains about a mismatched method reference.
- **`DrawContext.fill` / `.drawText` method names** - stable across recent
  1.20.x-1.21.x, but worth a quick check against the Yarn javadoc for your
  exact `yarn_mappings` build if something doesn't resolve.

If you want the panel to sit flush against the actual GUI box instead of a
fixed screen margin, that's the one piece that would need a small
`@Mixin(HandledScreen.class)` with an `@Accessor` for the protected `x`/`y`
fields - intentionally left out here to keep this simpler and more
version-resilient.

## Notes / things you may want to change

- `MIN_WEEKLY_VOLUME` and `MAX_MARGIN_PERCENT` in `BazaarFlipClient.java`
  control how aggressively illiquid/stale items get filtered. Lower the
  volume threshold if you want to see rarer, higher-margin items (riskier —
  your buy/sell order may sit unfilled for a long time).
- `OVERLAY_ROW_COUNT` in `BazaarFlipClient.java` and the sizing constants at
  the top of `BazaarOverlay.java` (`MARGIN_X`, `MARGIN_Y`, `PANEL_WIDTH`,
  `ROW_HEIGHT`) control the panel's position and how many rows it shows.
- The mod is `"environment": "client"` only — it doesn't touch servers, so
  it's safe to use on any server including Hypixel itself (it just reads
  public API data, same as a browser would).
- No API key needed for this. If you later want player/profile data (not
  included here), that *does* require a key from https://developer.hypixel.net.
