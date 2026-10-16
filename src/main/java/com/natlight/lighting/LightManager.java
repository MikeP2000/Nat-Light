/*
 * Copyright (c) 2026, MikeP2000
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.natlight.lighting;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.SpotanimID;

/**
 * Keeps track of the light sources in the scene and picks the ones nearest the player each frame.
 * Objects are tracked as they spawn and despawn, so the scene is only scanned once, when the plugin starts.
 */
public class LightManager
{
	public static final int MAX_LIGHTS = 32;
	private static final int MAX_DISTANCE = 25 * Perspective.LOCAL_TILE_SIZE;
	// Buildings get one ceiling light per cell of this many tiles that the building covers enough of
	private static final int INTERIOR_CELL_TILES = 5;
	private static final int INTERIOR_CELL_MIN_TILES = 3;
	// Skip a ceiling light when a real light source is already this close
	private static final int INTERIOR_SKIP_DISTANCE = 3 * Perspective.LOCAL_TILE_SIZE;

	// Object names are matched word by word, so "Wall torch" is a torch but "Fire altar" is excluded below
	private static final Map<String, LightType> NAME_WORDS = ImmutableMap.<String, LightType>builder()
		.put("torch", LightType.TORCH)
		.put("torches", LightType.TORCH)
		.put("sconce", LightType.TORCH)
		.put("fire", LightType.FIRE)
		.put("campfire", LightType.FIRE)
		.put("bonfire", LightType.FIRE)
		.put("fireplace", LightType.FIRE)
		.put("brazier", LightType.FIRE)
		.put("pyre", LightType.FIRE)
		.put("lamp", LightType.LAMP)
		.put("lantern", LightType.LAMP)
		.put("lamppost", LightType.LAMP)
		.put("chandelier", LightType.LAMP)
		.put("candle", LightType.CANDLE)
		.put("candles", LightType.CANDLE)
		.put("candelabra", LightType.CANDLE)
		.put("furnace", LightType.FURNACE)
		.put("range", LightType.FURNACE)
		.put("stove", LightType.FURNACE)
		.put("oven", LightType.FURNACE)
		.put("forge", LightType.FURNACE)
		.put("lava", LightType.LAVA)
		.put("stall", LightType.STALL)
		.build();

	// Words that mean the name isn't actually a light, e.g. "Fire altar" or "Fire obelisk"
	private static final Set<String> EXCLUDED_WORDS = ImmutableSet.of(
		"altar", "obelisk", "rune", "giant", "cape", "staff", "wizard", "spirit", "unlit", "remains", "ashes"
	);

	// Player-cast fire spells. Only the player's own projectiles are lit, so nothing highlights NPC attacks.
	private static final Set<Integer> FIRE_SPELL_PROJECTILES = ImmutableSet.of(
		SpotanimID.FIRESTRIKE_TRAVEL,
		SpotanimID.FIREBOLT_TRAVEL,
		SpotanimID.FIREBLAST_TRAVEL,
		SpotanimID.FIREWAVE_TRAVEL,
		SpotanimID.FIRESURGE_TRAVEL
	);

	private static final class Light
	{
		final LightType type;
		final float x;
		final float y;
		final float z;
		float phase;
		final int plane;
		/** Lights placed by this class always stay in their building; others are worked out each frame. */
		final boolean fixedBuilding;
		/** Building the light is confined to, {@link #OUTSIDE} for outdoors, 0 for not confined. */
		int building;
		float distance;
		/** Fades from 0 to 1 when the light first appears, so lights ease in instead of popping. */
		float level;
		/** Identifies the light across area reloads, from its type and world position. */
		long key;
		boolean keyed;

		Light(LightType type, float x, float y, float z, int plane, boolean fixedBuilding)
		{
			this.type = type;
			this.plane = plane;
			this.fixedBuilding = fixedBuilding;
			this.x = x;
			this.y = y;
			this.z = z;
			this.phase = (float) (((int) x * 31 + (int) z * 17) % 628) / 100f;
		}
	}

	private final Client client;
	private final Map<Integer, LightType> typeCache = new HashMap<>();
	private final Map<Long, Light> objectLights = new HashMap<>();
	private final List<Light> candidates = new ArrayList<>();
	private boolean rescan = true;

	// Buildings on the player's plane, and the ones lit because an NPC is in them or can walk to them
	private BuildingMap buildings;
	private int buildingPlane = -1;
	private int buildingBaseX;
	private int buildingBaseY;
	private boolean buildingsDirty = true;
	private boolean interiorEnabled = true;
	private final List<Light> buildingLights = new ArrayList<>();
	// lit buildings stay lit when the area reloads, keyed by the world position of their first tile
	private final Set<Long> rememberedLit = new HashSet<>();
	private boolean rememberedChanged;
	// fade levels of the lights from before the area reloaded, so lights that are still there don't fade in again
	private final Map<Long, Float> previousLevels = new HashMap<>();
	private int lastCycle = -1;
	private final Map<Integer, Integer> edgeCache = new HashMap<>();
	private static final int EDGE_NONE = 0;
	private static final int EDGE_DOOR = 1;
	private static final int EDGE_WINDOW = 2;

	/** xyz position and radius of each light picked this frame, in local units. */
	public final float[] positions = new float[MAX_LIGHTS * 4];
	/** rgb color times brightness of each light picked this frame. */
	public final float[] colors = new float[MAX_LIGHTS * 4];
	/** Building each light picked this frame belongs to, or 0 for lights that aren't confined to a building. */
	public final int[] lightBuildings = new int[MAX_LIGHTS];
	public int count;

	/**
	 * Building id (with angled-corner half) and spill of each tile on the player's plane, two shorts per tile in
	 * row-major order (y rows of x), for the shader to keep building lights inside their walls.
	 */
	public short[] buildingMask = new short[2];
	/** Building ids above this don't fit in the mask, so those buildings get no light. */
	private static final int MASK_MAX_BUILDING = 0x0FFF;
	private static final int MASK_LIT = 0x1000;
	/** Light building value for a light that is outdoors and must not shine into buildings. */
	private static final int OUTSIDE = -1;
	/** Client cycles a new light takes to fade in. */
	private static final int FADE_CYCLES = 60;
	/** Distance in local units over which lights fade out before the nearest-lights cut off. */
	private static final float CUTOFF_FADE = 3 * Perspective.LOCAL_TILE_SIZE;
	public int maskWidth = 1;
	public int maskHeight = 1;
	public boolean maskChanged = true;

	public LightManager(Client client)
	{
		this.client = client;
	}

	/** Forgets every light, e.g. when a new scene loads. Spawn events refill it. */
	public void clear()
	{
		previousLevels.clear();
		for (Light light : objectLights.values())
		{
			if (light.keyed)
			{
				previousLevels.put(light.key, light.level);
			}
		}
		for (Light light : buildingLights)
		{
			previousLevels.put(light.key, light.level);
		}
		objectLights.clear();
		buildingsDirty = true;
	}

	/** Rebuilds the building map next frame, e.g. once a newly loaded area's collision is in place. */
	public void markBuildingsDirty()
	{
		buildingsDirty = true;
	}

	/** Turns lighting of occupied buildings on or off. */
	public void setInteriorEnabled(boolean enabled)
	{
		if (enabled != interiorEnabled)
		{
			interiorEnabled = enabled;
			buildingsDirty = true;
		}
	}

	/** Forgets everything and scans the loaded scene once more, e.g. when the plugin starts. */
	public void reset()
	{
		clear();
		typeCache.clear();
		rescan = true;
	}

	/** Lights the building an NPC appears in, and every building it could walk to. */
	public void npcSpawned(NPC npc)
	{
		if (buildings == null || npc.getWorldView() == null || !npc.getWorldView().isTopLevel()
			|| npc.getWorldLocation().getPlane() != buildingPlane)
		{
			return;
		}
		LocalPoint lp = npc.getLocalLocation();
		int before = countLit();
		buildings.npcAt(lp.getSceneX(), lp.getSceneY());
		if (countLit() != before)
		{
			rememberLit();
			placeBuildingLights(npc.getWorldView());
			writeMask();
		}
	}

	public void objectSpawned(TileObject object)
	{
		if (object.getWorldView() == null || !object.getWorldView().isTopLevel())
		{
			return;
		}
		LightType type = classify(object.getId());
		if (type == null)
		{
			return;
		}
		int x = object.getX();
		int z = object.getY();
		if (object instanceof DecorativeObject)
		{
			// wall decorations sit on the face of their wall, so the offset says which side of it they light
			DecorativeObject decoration = (DecorativeObject) object;
			x += decoration.getXOffset();
			z += decoration.getYOffset();
		}
		// keyed on the first frame it's drawn, once the area has finished loading and its base is settled
		objectLights.put(object.getHash(), new Light(type, x, object.getZ() - type.height, z, object.getPlane(), false));
	}

	public void objectDespawned(TileObject object)
	{
		objectLights.remove(object.getHash());
	}

	/** Doors opening and closing change which buildings connect, so they trigger a rebuild. */
	public void wallObjectChanged(WallObject wall)
	{
		if (edgeKind(wall.getId()) == EDGE_DOOR)
		{
			buildingsDirty = true;
		}
	}

	/**
	 * Picks the lights nearest the player for this frame and fills {@link #positions} and {@link #colors}.
	 *
	 * @param brightness overall brightness multiplier for every light
	 */
	public void update(float brightness)
	{
		if (rescan)
		{
			rescan = false;
			clear();
			scanScene();
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv != null && (buildingsDirty || wv.getPlane() != buildingPlane))
		{
			buildingsDirty = false;
			rebuildBuildings(wv);
		}

		// ease new lights in over a second or so, measured in client cycles (20ms each)
		int cycle = client.getGameCycle();
		float step = lastCycle < 0 ? 0f : Math.max(0, Math.min(cycle - lastCycle, 50)) / (float) FADE_CYCLES;
		lastCycle = cycle;
		for (Light light : objectLights.values())
		{
			if (!light.keyed && wv != null)
			{
				// a light that was already showing before the area reloaded carries on at the same brightness
				keyLight(light, wv.getBaseX(), wv.getBaseY());
				Float level = previousLevels.get(light.key);
				if (level != null)
				{
					light.level = level;
				}
			}
			light.level = Math.min(1f, light.level + step);
		}
		for (Light light : buildingLights)
		{
			light.level = Math.min(1f, light.level + step);
		}

		count = 0;
		Player player = client.getLocalPlayer();
		if (player == null || brightness <= 0f)
		{
			return;
		}
		LocalPoint origin = player.getLocalLocation();
		float px = origin.getX();
		float pz = origin.getY();

		candidates.clear();
		for (Light light : objectLights.values())
		{
			addCandidate(light, px, pz);
		}
		for (Light light : buildingLights)
		{
			addCandidate(light, px, pz);
		}
		for (Projectile projectile : client.getProjectiles())
		{
			if (projectile.getSourceActor() == player && FIRE_SPELL_PROJECTILES.contains(projectile.getId()))
			{
				Light light = new Light(LightType.FIRE_SPELL, (float) projectile.getX(), (float) projectile.getZ(),
					(float) projectile.getY(), client.getTopLevelWorldView().getPlane(), false);
				light.level = 1f;
				addCandidate(light, px, pz);
			}
		}

		candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

		float time = client.getGameCycle() / 50f * 2f * (float) Math.PI;
		int n = Math.min(MAX_LIGHTS, candidates.size());
		// when there are more lights than fit, dim the farthest ones as they near the cut off, so they fade out as
		// the player walks away instead of switching off
		float cutoff = candidates.size() > MAX_LIGHTS ? (float) Math.sqrt(candidates.get(MAX_LIGHTS).distance) : Float.MAX_VALUE;
		for (int i = 0; i < n; i++)
		{
			Light light = candidates.get(i);
			LightType type = light.type;
			// two out-of-step waves make the flicker look less mechanical
			float wobble = (float) (Math.sin(time * 1.3f + light.phase) * 0.6 + Math.sin(time * 3.1f + light.phase * 2f) * 0.4);
			float edge = Math.min(1f, Math.max(0f, (cutoff - (float) Math.sqrt(light.distance)) / CUTOFF_FADE));
			float level = type.strength * brightness * (1f + type.flicker * wobble) * light.level * edge * edge * (3f - 2f * edge);

			int o = i * 4;
			positions[o] = light.x;
			positions[o + 1] = light.y;
			positions[o + 2] = light.z;
			positions[o + 3] = type.radius * Perspective.LOCAL_TILE_SIZE;
			colors[o] = type.r * level;
			colors[o + 1] = type.g * level;
			colors[o + 2] = type.b * level;
			colors[o + 3] = 0f;
			lightBuildings[i] = light.fixedBuilding ? light.building : sideOf(light);
		}
		count = n;
	}

	/**
	 * Which side of the walls a torch, stove or spell is on: the building it is in, or outdoors. The shader only lets
	 * it light surfaces on that side, so it doesn't shine through walls.
	 */
	private int sideOf(Light light)
	{
		if (buildings == null || light.plane != buildingPlane)
		{
			return 0;
		}
		int tx = (int) Math.floor(light.x / Perspective.LOCAL_TILE_SIZE);
		int ty = (int) Math.floor(light.z / Perspective.LOCAL_TILE_SIZE);
		if (tx < 0 || ty < 0 || tx >= buildings.width || ty >= buildings.height)
		{
			return 0;
		}
		int b = buildings.building[tx][ty];
		return b == 0 || b > MASK_MAX_BUILDING ? OUTSIDE : b;
	}

	private void addCandidate(Light light, float px, float pz)
	{
		float dx = light.x - px;
		float dz = light.z - pz;
		light.distance = dx * dx + dz * dz;
		if (light.distance < (float) MAX_DISTANCE * MAX_DISTANCE)
		{
			candidates.add(light);
		}
	}

	private void scanScene()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		Scene scene = wv.getScene();
		for (Tile[][] plane : scene.getTiles())
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					if (tile.getWallObject() != null)
					{
						objectSpawned(tile.getWallObject());
					}
					if (tile.getDecorativeObject() != null)
					{
						objectSpawned(tile.getDecorativeObject());
					}
					if (tile.getGroundObject() != null)
					{
						objectSpawned(tile.getGroundObject());
					}
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null)
						{
							objectSpawned(object);
						}
					}
				}
			}
		}
	}

	private void rebuildBuildings(WorldView wv)
	{
		buildings = null;
		buildingLights.clear();
		buildingPlane = wv.getPlane();
		maskChanged = true;
		maskWidth = 1;
		maskHeight = 1;
		buildingMask = new short[2];

		CollisionData[] maps = wv.getCollisionMaps();
		int plane = buildingPlane;
		// the map is built even with building lights off, since it also keeps torches and stoves on their side of
		// the walls
		if (maps == null || maps[plane] == null)
		{
			return;
		}

		int[][] flags = maps[plane].getFlags();
		int w = Math.min(flags.length, wv.getSizeX());
		int h = Math.min(flags[0].length, wv.getSizeY());
		int[][] f = new int[w][h];
		boolean[][] underRoof = new boolean[w][h];
		boolean[][] doorNorth = new boolean[w][h];
		boolean[][] doorEast = new boolean[w][h];
		boolean[][] windowNorth = new boolean[w][h];
		boolean[][] windowEast = new boolean[w][h];
		byte[][][] settings = wv.getTileSettings();
		Tile[][][] tiles = wv.getScene().getTiles();
		for (int x = 0; x < w; x++)
		{
			for (int y = 0; y < h; y++)
			{
				f[x][y] = flags[x][y];
				underRoof[x][y] = (settings[plane][x][y] & Constants.TILE_FLAG_UNDER_ROOF) != 0
					&& !isOpenFloor(tiles, settings, plane + 1, x, y);
				Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}
				WallObject wall = tile.getWallObject();
				if (wall != null)
				{
					int kind = edgeKind(wall.getId());
					if (kind != EDGE_NONE)
					{
						markEdges(kind == EDGE_DOOR ? doorNorth : windowNorth, kind == EDGE_DOOR ? doorEast : windowEast,
							x, y, wall.getOrientationA() | wall.getOrientationB());
					}
				}
				DecorativeObject decoration = tile.getDecorativeObject();
				if (decoration != null && edgeKind(decoration.getId()) == EDGE_WINDOW && wall != null)
				{
					markEdges(windowNorth, windowEast, x, y, wall.getOrientationA() | wall.getOrientationB());
				}
			}
		}

		buildings = new BuildingMap(f, underRoof, doorNorth, doorEast, windowNorth, windowEast);
		buildingBaseX = wv.getBaseX();
		buildingBaseY = wv.getBaseY();
		for (int b = 0; b < buildings.buildingCount(); b++)
		{
			if (rememberedLit.contains(buildingKey(b)))
			{
				buildings.lit.set(b, true);
			}
		}
		for (NPC npc : wv.npcs())
		{
			if (npc.getWorldLocation().getPlane() == plane)
			{
				LocalPoint lp = npc.getLocalLocation();
				buildings.npcAt(lp.getSceneX(), lp.getSceneY());
			}
		}
		rememberLit();
		placeBuildingLights(wv);

		maskWidth = w;
		maskHeight = h;
		buildingMask = new short[w * h * 2];
		writeMask();
	}

	private void writeMask()
	{
		int w = maskWidth;
		for (int y = 0; y < maskHeight; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int i = (y * w + x) * 2;
				int b = buildings.building[x][y];
				if (b > MASK_MAX_BUILDING)
				{
					b = 0;
				}
				else if (b > 0 && buildings.lit.get(b - 1))
				{
					b |= MASK_LIT;
				}
				// building id in the low 12 bits, whether it is lit in bit 12, which half of an angled-corner tile is
				// inside in the top 3
				buildingMask[i] = (short) (b | buildings.corner[x][y] << 13);
				int spill = buildings.spill[x][y];
				buildingMask[i + 1] = (short) ((spill & BuildingMap.SPILL_ID_MASK) > MASK_MAX_BUILDING ? 0 : spill);
			}
		}
		maskChanged = true;
	}

	/**
	 * Whether a tile is an outdoor floor, such as the walkway on top of a town wall. What's under it is covered but
	 * isn't a building.
	 */
	private static boolean isOpenFloor(Tile[][][] tiles, byte[][][] settings, int plane, int x, int y)
	{
		if (plane >= tiles.length || (settings[plane][x][y] & Constants.TILE_FLAG_UNDER_ROOF) != 0)
		{
			return false;
		}
		Tile tile = tiles[plane][x][y];
		return tile != null && (tile.getSceneTilePaint() != null || tile.getSceneTileModel() != null);
	}

	private static void markEdges(boolean[][] north, boolean[][] east, int x, int y, int orientation)
	{
		if ((orientation & 2) != 0)
		{
			north[x][y] = true;
		}
		if ((orientation & 8) != 0 && y > 0)
		{
			north[x][y - 1] = true;
		}
		if ((orientation & 4) != 0)
		{
			east[x][y] = true;
		}
		if ((orientation & 1) != 0 && x > 0)
		{
			east[x - 1][y] = true;
		}
	}

	/** Puts ceiling lights in each lit building, spread over the cells it covers. */
	private void placeBuildingLights(WorldView wv)
	{
		Map<Long, Float> oldLevels = new HashMap<>(previousLevels);
		for (Light light : buildingLights)
		{
			oldLevels.put(light.key, light.level);
		}
		buildingLights.clear();
		if (!interiorEnabled)
		{
			return;
		}
		int plane = buildingPlane;
		Map<Integer, int[]> cells = new HashMap<>();
		for (int b = 0; b < buildings.buildingCount(); b++)
		{
			if (!buildings.lit.get(b) || b + 1 > MASK_MAX_BUILDING)
			{
				continue;
			}
			cells.clear();
			int[] tiles = buildings.buildingTiles.get(b);
			for (int t : tiles)
			{
				int tx = t >> 16;
				int ty = t & 0xFFFF;
				// cells line up with the world, not the loaded area, so the lights stay put when the area reloads
				int cell = (buildingBaseX + tx) / INTERIOR_CELL_TILES << 16 | (buildingBaseY + ty) / INTERIOR_CELL_TILES;
				int[] sum = cells.computeIfAbsent(cell, k -> new int[3]);
				sum[0] += tx;
				sum[1] += ty;
				sum[2]++;
			}
			for (int[] sum : cells.values())
			{
				if (sum[2] < INTERIOR_CELL_MIN_TILES)
				{
					continue;
				}
				// the building tile nearest the middle of this part, so the light is never outside the building
				float cx = (float) sum[0] / sum[2];
				float cy = (float) sum[1] / sum[2];
				int best = tiles[0];
				float bestDistance = Float.MAX_VALUE;
				for (int t : tiles)
				{
					float dx = (t >> 16) - cx;
					float dy = (t & 0xFFFF) - cy;
					float d = dx * dx + dy * dy;
					if (d < bestDistance)
					{
						bestDistance = d;
						best = t;
					}
				}
				int x = (best >> 16) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
				int z = (best & 0xFFFF) * Perspective.LOCAL_TILE_SIZE + Perspective.LOCAL_TILE_SIZE / 2;
				if (hasObjectLightNear(x, z))
				{
					continue;
				}
				int y = wv.getTileHeight(x, z, plane) - LightType.INTERIOR.height;
				Light light = new Light(LightType.INTERIOR, x, y, z, plane, true);
				light.building = b + 1;
				keyLight(light, buildingBaseX, buildingBaseY);
				Float level = oldLevels.get(light.key);
				if (level != null)
				{
					light.level = level;
				}
				buildingLights.add(light);
			}
		}
	}

	private boolean hasObjectLightNear(int x, int z)
	{
		for (Light light : objectLights.values())
		{
			float dx = light.x - x;
			float dz = light.z - z;
			if (dx * dx + dz * dz < (float) INTERIOR_SKIP_DISTANCE * INTERIOR_SKIP_DISTANCE)
			{
				return true;
			}
		}
		return false;
	}

	private int countLit()
	{
		int n = 0;
		for (boolean lit : buildings.lit)
		{
			if (lit)
			{
				n++;
			}
		}
		return n;
	}

	private void rememberLit()
	{
		for (int b = 0; b < buildings.buildingCount(); b++)
		{
			if (buildings.lit.get(b) && rememberedLit.add(buildingKey(b)))
			{
				rememberedChanged = true;
			}
		}
	}

	private static void keyLight(Light light, int baseX, int baseY)
	{
		// quarter-tile world position, plus type and plane
		long wx = baseX * 4L + (long) Math.floor(light.x / 32f);
		long wy = baseY * 4L + (long) Math.floor(light.z / 32f);
		light.keyed = true;
		// spread the flicker so neighbouring torches don't pulse together, from the world position so it carries on
		// smoothly when the area reloads
		light.phase = (float) ((wx * 31 + wy * 17) % 628) / 100f;
		light.key = (long) light.type.ordinal() << 52 | (long) light.plane << 48 | (wx & 0xFFFFFF) << 24 | (wy & 0xFFFFFF);
	}

	/** Whether the set of remembered lit buildings changed since the last {@link #saveLit()}. */
	public boolean litChanged()
	{
		return rememberedChanged;
	}

	/** The remembered lit buildings, as a compact string for saving between sessions. */
	public String saveLit()
	{
		rememberedChanged = false;
		StringBuilder sb = new StringBuilder();
		for (long key : rememberedLit)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(Long.toString(key, 36));
		}
		return sb.toString();
	}

	/** Restores lit buildings saved by {@link #saveLit()}. */
	public void loadLit(String saved)
	{
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String part : saved.split(","))
		{
			try
			{
				rememberedLit.add(Long.parseLong(part, 36));
			}
			catch (NumberFormatException e)
			{
				// skip anything that isn't a key
			}
		}
		buildingsDirty = true;
	}

	private long buildingKey(int b)
	{
		int t = buildings.buildingTiles.get(b)[0];
		long x = buildingBaseX + (t >> 16);
		long y = buildingBaseY + (t & 0xFFFF);
		return (long) buildingPlane << 40 | x << 20 | y;
	}

	/** Whether an object is a door (or gate) or a window, from its name and actions. */
	private int edgeKind(int id)
	{
		Integer cached = edgeCache.get(id);
		if (cached != null)
		{
			return cached;
		}
		int kind = EDGE_NONE;
		ObjectComposition def = client.getObjectDefinition(id);
		if (def != null && def.getName() != null)
		{
			String name = def.getName().toLowerCase();
			if (name.contains("window"))
			{
				kind = EDGE_WINDOW;
			}
			else if (name.contains("door") || name.contains("gate"))
			{
				kind = EDGE_DOOR;
			}
			else if (def.getActions() != null)
			{
				for (String action : def.getActions())
				{
					if ("Open".equals(action) || "Close".equals(action))
					{
						kind = EDGE_DOOR;
						break;
					}
				}
			}
		}
		edgeCache.put(id, kind);
		return kind;
	}

	private LightType classify(int id)
	{
		ObjectComposition def = client.getObjectDefinition(id);
		if (def == null)
		{
			return null;
		}
		// Objects that change with game state (e.g. a brazier that can be lit) are looked up fresh each time
		if (def.getImpostorIds() != null)
		{
			ObjectComposition impostor = def.getImpostor();
			return impostor == null ? null : classify(impostor);
		}
		if (typeCache.containsKey(id))
		{
			return typeCache.get(id);
		}
		LightType type = classify(def);
		typeCache.put(id, type);
		return type;
	}

	private static LightType classify(ObjectComposition def)
	{
		String name = def.getName();
		if (name == null || name.equals("null"))
		{
			return null;
		}
		// unlit torches and candles offer "Light"
		String[] actions = def.getActions();
		if (actions != null)
		{
			for (String action : actions)
			{
				if ("Light".equalsIgnoreCase(action))
				{
					return null;
				}
			}
		}

		LightType type = null;
		for (String word : name.toLowerCase().split("[^a-z]+"))
		{
			if (EXCLUDED_WORDS.contains(word))
			{
				return null;
			}
			if (type == null)
			{
				type = NAME_WORDS.get(word);
			}
		}
		return type;
	}
}
