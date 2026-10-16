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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.CollisionDataFlag;

/**
 * Splits one plane of the scene into buildings (connected areas under a roof, separated by walls and doors) and
 * works out which buildings an NPC could walk into. For walking, doors count as passable, since NPCs and players
 * can open them.
 * <p>
 * It also marks where a building's light may spill outside: up to two tiles out through windows and open doorways.
 * A window is a wall named as one, or a wall that blocks movement but not line of sight.
 */
public class BuildingMap
{
	static final int MIN_BUILDING_TILES = 4;
	private static final int SPILL_TILES = 2;
	// A roofed area with more of its outline open than this is an overhang or canopy, not a room
	private static final float MAX_OPEN_OUTLINE = 0.3f;
	/** Spill values carry the building id in the low 14 bits and how strong the spill is (1-2) above them. */
	public static final int SPILL_ID_MASK = 0x3FFF;
	public static final int SPILL_LEVEL_SHIFT = 14;

	private static final int[] DX = {0, 1, 0, -1};
	private static final int[] DY = {1, 0, -1, 0};
	private static final int[] MOVE = {
		CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_EAST,
		CollisionDataFlag.BLOCK_MOVEMENT_SOUTH, CollisionDataFlag.BLOCK_MOVEMENT_WEST,
	};
	private static final int[] SIGHT = {
		CollisionDataFlag.BLOCK_LINE_OF_SIGHT_NORTH, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_EAST,
		CollisionDataFlag.BLOCK_LINE_OF_SIGHT_SOUTH, CollisionDataFlag.BLOCK_LINE_OF_SIGHT_WEST,
	};

	public final int width;
	public final int height;
	/** Building id of each tile, 0 outside any building. Ids start at 1. */
	public final int[][] building;
	/**
	 * Building whose light may spill onto each tile through a window or doorway, with its strength
	 * (2 next to the opening, 1 a tile further out), or 0 for none. See {@link #SPILL_ID_MASK}.
	 */
	public final int[][] spill;
	/** Tiles of each building, as x << 16 | y, indexed by id - 1. */
	public final List<int[]> buildingTiles = new ArrayList<>();
	/** Whether each building, indexed by id - 1, is lit because an NPC is in it or can walk to it. */
	public final List<Boolean> lit = new ArrayList<>();

	private final int[][] flags;
	private final boolean[][] doorNorth;
	private final boolean[][] doorEast;
	private final boolean[][] windowNorth;
	private final boolean[][] windowEast;
	// walkable area each tile belongs to, 0 if it can't be stood on
	private final int[][] area;
	private final List<List<Integer>> areaBuildings = new ArrayList<>();

	/**
	 * @param flags     collision flags of the plane, indexed [x][y]
	 * @param underRoof whether each tile is under a roof
	 * @param doorNorth whether the edge between (x, y) and (x, y + 1) is a door
	 * @param doorEast  whether the edge between (x, y) and (x + 1, y) is a door
	 * @param windowNorth whether the edge between (x, y) and (x, y + 1) has a window object
	 * @param windowEast  whether the edge between (x, y) and (x + 1, y) has a window object
	 */
	public BuildingMap(int[][] flags, boolean[][] underRoof, boolean[][] doorNorth, boolean[][] doorEast,
		boolean[][] windowNorth, boolean[][] windowEast)
	{
		this.windowNorth = windowNorth;
		this.windowEast = windowEast;
		this.width = flags.length;
		this.height = flags[0].length;
		this.flags = flags;
		this.doorNorth = doorNorth;
		this.doorEast = doorEast;
		this.building = new int[width][height];
		this.spill = new int[width][height];
		this.area = new int[width][height];

		findBuildings(underRoof);
		findAreas();
		findSpill();
	}

	/** Marks the building an NPC stands in, and every building it can walk to, as lit. */
	public void npcAt(int x, int y)
	{
		if (!inBounds(x, y))
		{
			return;
		}
		if (building[x][y] != 0)
		{
			lit.set(building[x][y] - 1, true);
		}
		int a = area[x][y];
		if (a != 0)
		{
			for (int b : areaBuildings.get(a - 1))
			{
				lit.set(b - 1, true);
			}
		}
	}

	public int buildingCount()
	{
		return buildingTiles.size();
	}

	private void findBuildings(boolean[][] underRoof)
	{
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		List<Integer> tiles = new ArrayList<>();
		int next = 1;
		for (int x = 0; x < width; x++)
		{
			for (int y = 0; y < height; y++)
			{
				if (!underRoof[x][y] || building[x][y] != 0)
				{
					continue;
				}
				tiles.clear();
				building[x][y] = next;
				queue.add(x << 16 | y);
				while (!queue.isEmpty())
				{
					int t = queue.poll();
					tiles.add(t);
					int tx = t >> 16;
					int ty = t & 0xFFFF;
					for (int d = 0; d < 4; d++)
					{
						int nx = tx + DX[d];
						int ny = ty + DY[d];
						// rooms split at doors, so a porch roof outside the front door stays separate
						if (inBounds(nx, ny) && underRoof[nx][ny] && building[nx][ny] == 0 && !blocked(tx, ty, d))
						{
							building[nx][ny] = next;
							queue.add(nx << 16 | ny);
						}
					}
				}

				if (tiles.size() < MIN_BUILDING_TILES || isOpen(tiles, next, underRoof))
				{
					// too small to be a building, e.g. a lone roofed tile, or roofed but not walled in
					for (int t : tiles)
					{
						building[t >> 16][t & 0xFFFF] = -1;
					}
					continue;
				}
				buildingTiles.add(tiles.stream().mapToInt(Integer::intValue).toArray());
				lit.add(false);
				next++;
			}
		}
		for (int x = 0; x < width; x++)
		{
			for (int y = 0; y < height; y++)
			{
				if (building[x][y] < 0)
				{
					building[x][y] = 0;
				}
			}
		}
	}

	private void findAreas()
	{
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int next = 1;
		for (int x = 0; x < width; x++)
		{
			for (int y = 0; y < height; y++)
			{
				if (area[x][y] != 0 || !walkable(x, y))
				{
					continue;
				}
				List<Integer> buildings = new ArrayList<>();
				area[x][y] = next;
				queue.add(x << 16 | y);
				while (!queue.isEmpty())
				{
					int t = queue.poll();
					int tx = t >> 16;
					int ty = t & 0xFFFF;
					int b = building[tx][ty];
					if (b != 0 && !buildings.contains(b))
					{
						buildings.add(b);
					}
					for (int d = 0; d < 4; d++)
					{
						int nx = tx + DX[d];
						int ny = ty + DY[d];
						if (inBounds(nx, ny) && area[nx][ny] == 0 && walkable(nx, ny) && canPass(tx, ty, d))
						{
							area[nx][ny] = next;
							queue.add(nx << 16 | ny);
						}
					}
				}
				areaBuildings.add(buildings);
				next++;
			}
		}
	}

	private void findSpill()
	{
		for (int b = 1; b <= buildingTiles.size(); b++)
		{
			for (int t : buildingTiles.get(b - 1))
			{
				int tx = t >> 16;
				int ty = t & 0xFFFF;
				for (int d = 0; d < 4; d++)
				{
					int nx = tx + DX[d];
					int ny = ty + DY[d];
					if (!inBounds(nx, ny) || building[nx][ny] == b)
					{
						continue;
					}
					boolean open = !blocked(tx, ty, d);
					boolean window = !open && !isDoor(tx, ty, d) && (isWindow(tx, ty, d) || !sightBlocked(tx, ty, d));
					if (!open && !window)
					{
						continue;
					}
					// carry the light a couple of tiles out, stopping at the next wall
					int sx = tx;
					int sy = ty;
					for (int i = 0; i < SPILL_TILES; i++)
					{
						if (i > 0 && (!inBounds(sx + DX[d], sy + DY[d]) || blocked(sx, sy, d)))
						{
							break;
						}
						sx += DX[d];
						sy += DY[d];
						if (!inBounds(sx, sy) || building[sx][sy] != 0)
						{
							break;
						}
						int level = SPILL_TILES - i;
						if ((spill[sx][sy] >> SPILL_LEVEL_SHIFT) < level)
						{
							spill[sx][sy] = b | level << SPILL_LEVEL_SHIFT;
						}
					}
				}
			}
		}
	}

	/** Whether too much of an area's outline has no wall, e.g. the ground under a roof's overhang. */
	private boolean isOpen(List<Integer> tiles, int id, boolean[][] underRoof)
	{
		int outline = 0;
		int open = 0;
		for (int t : tiles)
		{
			int tx = t >> 16;
			int ty = t & 0xFFFF;
			for (int d = 0; d < 4; d++)
			{
				int nx = tx + DX[d];
				int ny = ty + DY[d];
				if (inBounds(nx, ny) && building[nx][ny] == id)
				{
					continue;
				}
				outline++;
				if (inBounds(nx, ny) && !underRoof[nx][ny] && !blocked(tx, ty, d) && !isDoor(tx, ty, d))
				{
					open++;
				}
			}
		}
		return open > outline * MAX_OPEN_OUTLINE;
	}

	private boolean walkable(int x, int y)
	{
		return (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	private boolean canPass(int x, int y, int d)
	{
		return !blocked(x, y, d) || isDoor(x, y, d);
	}

	/** Whether a wall blocks moving from (x, y) in direction d. */
	private boolean blocked(int x, int y, int d)
	{
		int nx = x + DX[d];
		int ny = y + DY[d];
		return (flags[x][y] & MOVE[d]) != 0 || (flags[nx][ny] & MOVE[(d + 2) % 4]) != 0;
	}

	private boolean sightBlocked(int x, int y, int d)
	{
		int nx = x + DX[d];
		int ny = y + DY[d];
		return (flags[x][y] & SIGHT[d]) != 0 || (flags[nx][ny] & SIGHT[(d + 2) % 4]) != 0;
	}

	private boolean isDoor(int x, int y, int d)
	{
		return edge(doorNorth, doorEast, x, y, d);
	}

	private boolean isWindow(int x, int y, int d)
	{
		return edge(windowNorth, windowEast, x, y, d);
	}

	private static boolean edge(boolean[][] north, boolean[][] east, int x, int y, int d)
	{
		switch (d)
		{
			case 0:
				return north[x][y];
			case 1:
				return east[x][y];
			case 2:
				return north[x][y - 1];
			default:
				return east[x - 1][y];
		}
	}

	private boolean inBounds(int x, int y)
	{
		return x >= 0 && y >= 0 && x < width && y < height;
	}
}
