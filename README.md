# Nat Light

A RuneLite GPU renderer with natural lighting: a configurable day/night cycle, light from torches and spells, and translucent water with sun and moon reflections.

Nat Light is a fork of RuneLite's stock GPU plugin (from `runelite-parent-1.13.2`), so it conflicts with the GPU plugin and turns it off when enabled.

## Lighting settings

- **Time of day:** run a day/night cycle, or keep a static time.
- **Day length / Night length:** how many real minutes the day and the night each last in the cycle.
- **Static time (hour):** the hour to hold when the time is static. Sunrise is 6, sunset is 18.
- **Sun strength:** how strongly the sun and moon shade the scene. 0 keeps the stock shading.
- **Night brightness:** how bright the scene stays at night.
- **Light sources:** torches, fires, lamps, candles, furnaces, lava, market stalls, bank booths, street lamps around the Grand Exchange and your own fire spells light up their surroundings. They're strongest at night and underground.
- **Light occupied buildings:** adds a soft ceiling light to roofed buildings that an NPC is in or can walk to (doors count as passable). The light stays inside the walls and only spills out a couple of tiles through windows and open doorways. Buildings stay lit once found, including next time you play.
- **Show building map (debug):** colors the ground by how building lights see it. Green is a lit building, red is a building with no NPC, blue is light spilling out of windows and doorways, grey or orange is a roofed area too small or too open to count as a building, and a dot marks each light (yellow for ceiling lights, white for the rest).
- **Light source brightness:** how bright those light sources are.

Underground areas and dungeons are not affected by the time of day.

## Running

```
./gradlew run
```

This launches a development client with Nat Light loaded. See [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) to log in.
