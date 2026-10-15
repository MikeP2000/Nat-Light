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
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
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
	// Interior lights are shared by every NPC in the same cell of this many tiles, so a building gets one or two, not one per person
	private static final int INTERIOR_CELL_TILES = 5;
	// Skip an interior light when a real light source is already this close
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
		final float phase;
		float distance;

		Light(LightType type, float x, float y, float z)
		{
			this.type = type;
			this.x = x;
			this.y = y;
			this.z = z;
			// spread the flicker so neighbouring torches don't pulse together
			this.phase = (float) (((int) x * 31 + (int) z * 17) % 628) / 100f;
		}
	}

	private final Client client;
	private final Map<Integer, LightType> typeCache = new HashMap<>();
	private final Map<Long, Light> objectLights = new HashMap<>();
	private final List<Light> candidates = new ArrayList<>();
	private final Map<Integer, Integer> npcCells = new HashMap<>();
	private final Map<Integer, InteriorLight> interiorLights = new HashMap<>();
	private boolean interiorEnabled = true;

	private static final class InteriorLight
	{
		final Light light;
		int npcs;

		InteriorLight(Light light)
		{
			this.light = light;
		}
	}
	private boolean rescan = true;

	/** xyz position and radius of each light picked this frame, in local units. */
	public final float[] positions = new float[MAX_LIGHTS * 4];
	/** rgb color times brightness of each light picked this frame. */
	public final float[] colors = new float[MAX_LIGHTS * 4];
	public int count;

	public LightManager(Client client)
	{
		this.client = client;
	}

	/** Forgets every light, e.g. when a new scene loads. Spawn events refill it. */
	public void clear()
	{
		objectLights.clear();
		npcCells.clear();
		interiorLights.clear();
	}

	/** Turns the lights for occupied buildings on or off. Turning them on picks up NPCs already in the scene. */
	public void setInteriorEnabled(boolean enabled)
	{
		if (enabled != interiorEnabled)
		{
			interiorEnabled = enabled;
			rescan = true;
		}
	}

	/** Forgets everything and scans the loaded scene once more, e.g. when the plugin starts. */
	public void reset()
	{
		clear();
		typeCache.clear();
		rescan = true;
	}

	/**
	 * Lights the building an NPC is standing in when it spawns, if it is under a roof and the building has no
	 * light source nearby. The light stays where the NPC spawned rather than following it around.
	 */
	public void npcSpawned(NPC npc)
	{
		if (!interiorEnabled || npc.getWorldView() == null || !npc.getWorldView().isTopLevel())
		{
			return;
		}
		WorldView wv = npc.getWorldView();
		LocalPoint lp = npc.getLocalLocation();
		int plane = wv.getPlane();
		int sx = lp.getSceneX();
		int sy = lp.getSceneY();
		if (sx < 0 || sy < 0 || sx >= wv.getSizeX() || sy >= wv.getSizeY()
			|| (wv.getTileSettings()[plane][sx][sy] & Constants.TILE_FLAG_UNDER_ROOF) == 0)
		{
			return;
		}

		int cell = (plane << 24) | ((sx / INTERIOR_CELL_TILES) << 12) | (sy / INTERIOR_CELL_TILES);
		InteriorLight interior = interiorLights.get(cell);
		if (interior == null)
		{
			int x = lp.getX();
			int z = lp.getY();
			if (hasObjectLightNear(x, z))
			{
				return;
			}
			int y = wv.getTileHeight(x, z, plane) - LightType.INTERIOR.height;
			interior = new InteriorLight(new Light(LightType.INTERIOR, x, y, z));
			interiorLights.put(cell, interior);
		}
		interior.npcs++;
		npcCells.put(npc.getIndex(), cell);
	}

	public void npcDespawned(NPC npc)
	{
		Integer cell = npcCells.remove(npc.getIndex());
		if (cell == null)
		{
			return;
		}
		InteriorLight interior = interiorLights.get(cell);
		if (interior != null && --interior.npcs <= 0)
		{
			interiorLights.remove(cell);
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
		objectLights.put(object.getHash(), new Light(type, object.getX(), object.getZ() - type.height, object.getY()));
	}

	public void objectDespawned(TileObject object)
	{
		objectLights.remove(object.getHash());
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
		for (InteriorLight interior : interiorLights.values())
		{
			addCandidate(interior.light, px, pz);
		}
		for (Projectile projectile : client.getProjectiles())
		{
			if (projectile.getSourceActor() == player && FIRE_SPELL_PROJECTILES.contains(projectile.getId()))
			{
				addCandidate(new Light(LightType.FIRE_SPELL, (float) projectile.getX(), (float) projectile.getZ(), (float) projectile.getY()), px, pz);
			}
		}

		candidates.sort((a, b) -> Float.compare(a.distance, b.distance));

		float time = client.getGameCycle() / 50f * 2f * (float) Math.PI;
		int n = Math.min(MAX_LIGHTS, candidates.size());
		for (int i = 0; i < n; i++)
		{
			Light light = candidates.get(i);
			LightType type = light.type;
			// two out-of-step waves make the flicker look less mechanical
			float wobble = (float) (Math.sin(time * 1.3f + light.phase) * 0.6 + Math.sin(time * 3.1f + light.phase * 2f) * 0.4);
			float level = type.strength * brightness * (1f + type.flicker * wobble);

			int o = i * 4;
			positions[o] = light.x;
			positions[o + 1] = light.y;
			positions[o + 2] = light.z;
			positions[o + 3] = type.radius * Perspective.LOCAL_TILE_SIZE;
			colors[o] = type.r * level;
			colors[o + 1] = type.g * level;
			colors[o + 2] = type.b * level;
			colors[o + 3] = 0f;
		}
		count = n;
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
		// objects first, so NPC interiors can see the light sources already there
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
		for (NPC npc : wv.npcs())
		{
			npcSpawned(npc);
		}
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
