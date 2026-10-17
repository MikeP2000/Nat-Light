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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.CollisionDataFlag;

/**
 * Splits one plane of the scene into buildings: connected areas under a roof, separated by walls and doors.
 * <p>
 * It also marks where a building's light may spill outside: up to two tiles out through windows and open doorways.
 * A window is a wall named as one, or a wall that blocks movement but not line of sight.
 */
public class BuildingMap
{
	static final int MIN_BUILDING_TILES = 4;
	private static final int SPILL_TILES = 2;
	/** Buildings with a gap in their walls (other than a door) must be at least this big... */
	private static final int MIN_OPEN_BUILDING_TILES = 9;
	/**
	 * ...and have at most this much of their outline in gaps wider than a doorway. More than that and it's an
	 * overhang, canopy or lean-to, not a room.
	 */
	private static final float MAX_OPEN_OUTLINE_SMALL = 0.15f;
	/** Gaps in a wall up to this many tiles wide are doorways or archways, which rooms have. */
	private static final int MAX_DOORWAY_WIDTH = 3;
	/** Spill values carry the building id in the low 14 bits and how strong the spill is (1-2) above them. */
	public static final int SPILL_ID_MASK = 0x3FFF;
	public static final int SPILL_LEVEL_SHIFT = 14;

	/**
	 * Corner tiles with an angled wall through them are only half inside. These say which half: the triangle
	 * towards the named corner.
	 */
	public static final int CORNER_NONE = 0;
	public static final int CORNER_NE = 1;
	public static final int CORNER_NW = 2;
	public static final int CORNER_SE = 3;
	public static final int CORNER_SW = 4;

	/** Steps for directions north, east, south and west. */
	static final int[] DX = {0, 1, 0, -1};
	static final int[] DY = {1, 0, -1, 0};
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
	/** Which half of each angled-corner tile is inside, see {@link #CORNER_NE}. */
	public final int[][] corner;
	/** For the debug view: why a roofed tile isn't part of a building, {@link #REJECT_SMALL} or {@link #REJECT_OPEN}. */
	public final int[][] rejected;
	public static final int REJECT_SMALL = 2;
	public static final int REJECT_OPEN = 3;
	/** Tiles of each building, as x << 16 | y, indexed by id - 1. */
	public final List<int[]> buildingTiles = new ArrayList<>();

	private final int[][] flags;
	private final boolean[][] doorNorth;
	private final boolean[][] doorEast;
	private final boolean[][] windowNorth;
	private final boolean[][] windowEast;

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
		this.corner = new int[width][height];
		this.rejected = new int[width][height];

		findCorners(underRoof);
		findBuildings(underRoof);
		findSpill();
	}

	public int buildingCount()
	{
		return buildingTiles.size();
	}

	private void findBuildings(boolean[][] underRoof)
	{
		// first split the roofed area into rooms at anything that blocks movement, so a porch roof outside a wall or
		// window stays separate from the room inside it
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		List<int[]> rooms = new ArrayList<>();
		List<Integer> tiles = new ArrayList<>();
		for (int x = 0; x < width; x++)
		{
			for (int y = 0; y < height; y++)
			{
				if (!underRoof[x][y] || building[x][y] != 0)
				{
					continue;
				}
				int room = rooms.size() + 1;
				tiles.clear();
				building[x][y] = room;
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
						if (inBounds(nx, ny) && underRoof[nx][ny] && building[nx][ny] == 0 && !blocked(tx, ty, d))
						{
							building[nx][ny] = room;
							queue.add(nx << 16 | ny);
						}
					}
				}
				rooms.add(tiles.stream().mapToInt(Integer::intValue).toArray());
			}
		}

		// then join walled-in rooms that are only split by something you can see over, like a shop counter or a rope
		// barrier, so the whole room lights up together. Walls and doors still keep rooms apart.
		int[] parent = new int[rooms.size() + 1];
		boolean[] open = new boolean[rooms.size() + 1];
		for (int r = 1; r <= rooms.size(); r++)
		{
			parent[r] = r;
			open[r] = isOpen(rooms.get(r - 1), r, underRoof);
		}
		for (int r = 1; r <= rooms.size(); r++)
		{
			if (open[r])
			{
				continue;
			}
			for (int t : rooms.get(r - 1))
			{
				int tx = t >> 16;
				int ty = t & 0xFFFF;
				for (int d = 0; d < 4; d++)
				{
					int nx = tx + DX[d];
					int ny = ty + DY[d];
					if (!inBounds(nx, ny))
					{
						continue;
					}
					int other = building[nx][ny];
					if (other > 0 && other != r && !open[other] && !sightBlocked(tx, ty, d) && !isDoor(tx, ty, d))
					{
						parent[find(parent, other)] = find(parent, r);
					}
				}
			}
		}

		// gather the joined rooms into buildings, dropping ones too small or too open to be a building
		List<List<Integer>> groups = new ArrayList<>();
		int[] group = new int[rooms.size() + 1];
		for (int r = 1; r <= rooms.size(); r++)
		{
			int root = find(parent, r);
			if (group[root] == 0)
			{
				groups.add(new ArrayList<>());
				group[root] = groups.size();
			}
			for (int t : rooms.get(r - 1))
			{
				groups.get(group[root] - 1).add(t);
			}
		}
		int next = 1;
		for (int g = 0; g < groups.size(); g++)
		{
			List<Integer> groupTiles = groups.get(g);
			// mark the group with a temporary id no room uses, so isOpen sees its outline
			int mark = -(g + 2);
			for (int t : groupTiles)
			{
				building[t >> 16][t & 0xFFFF] = mark;
			}
			boolean small = groupTiles.size() < MIN_BUILDING_TILES;
			if (small || isOpen(groupTiles.stream().mapToInt(Integer::intValue).toArray(), mark, underRoof))
			{
				for (int t : groupTiles)
				{
					rejected[t >> 16][t & 0xFFFF] = small ? REJECT_SMALL : REJECT_OPEN;
				}
				// too small to be a building, e.g. a lone roofed tile, or roofed but not walled in
				continue;
			}
			for (int t : groupTiles)
			{
				building[t >> 16][t & 0xFFFF] = next;
			}
			buildingTiles.add(groupTiles.stream().mapToInt(Integer::intValue).toArray());
			next++;
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

	private static int find(int[] parent, int r)
	{
		while (parent[r] != r)
		{
			parent[r] = parent[parent[r]];
			r = parent[r];
		}
		return r;
	}

	private void findSpill()
	{
		for (int b = 1; b <= buildingTiles.size(); b++)
		{
			for (int t : buildingTiles.get(b - 1))
			{
				int tx = t >> 16;
				int ty = t & 0xFFFF;
				if (corner[tx][ty] != CORNER_NONE)
				{
					// the open sides of an angled corner are outside the wall, not a way out
					continue;
				}
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

	/**
	 * Finds roofed tiles cut by an angled wall: tiles that can't be stood on and open, with no wall, onto unroofed
	 * ground on exactly two sides that meet at a corner. The inside is the opposite corner.
	 */
	private void findCorners(boolean[][] underRoof)
	{
		for (int x = 0; x < width; x++)
		{
			for (int y = 0; y < height; y++)
			{
				if (!underRoof[x][y] || walkable(x, y))
				{
					continue;
				}
				int outside = 0;
				for (int d = 0; d < 4; d++)
				{
					int nx = x + DX[d];
					int ny = y + DY[d];
					if (inBounds(nx, ny) && !underRoof[nx][ny] && !blocked(x, y, d))
					{
						outside |= 1 << d;
					}
				}
				// bits: 1 north, 2 east, 4 south, 8 west
				switch (outside)
				{
					case 4 | 8:
						corner[x][y] = CORNER_NE;
						break;
					case 4 | 2:
						corner[x][y] = CORNER_NW;
						break;
					case 1 | 8:
						corner[x][y] = CORNER_SE;
						break;
					case 1 | 2:
						corner[x][y] = CORNER_SW;
						break;
					default:
						break;
				}
			}
		}
	}

	/**
	 * Whether too much of an area's outline has no wall, e.g. the ground under a roof's overhang. Gaps no wider than a
	 * doorway, like an archway into a hall or the ends of a gatehouse passage, don't count, except in a space too
	 * small to be a room, such as an alcove under a wall walkway.
	 */
	private boolean isOpen(int[] tiles, int id, boolean[][] underRoof)
	{
		int outline = 0;
		Set<Long> openEdges = new HashSet<>();
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
				if (inBounds(nx, ny) && corner[tx][ty] == CORNER_NONE && !underRoof[nx][ny] && !blocked(tx, ty, d) && !isDoor(tx, ty, d))
				{
					openEdges.add((long) t << 2 | d);
				}
			}
		}
		if (openEdges.isEmpty())
		{
			return false;
		}
		if (tiles.length < MIN_OPEN_BUILDING_TILES)
		{
			return true;
		}

		// measure each gap along the wall; only ones wider than a doorway make the area open
		int wide = 0;
		Set<Long> seen = new HashSet<>();
		ArrayDeque<Long> queue = new ArrayDeque<>();
		for (long start : openEdges)
		{
			if (!seen.add(start))
			{
				continue;
			}
			int d = (int) (start & 3);
			// a gap in a north or south wall runs east-west, and the other way round
			int sx = d % 2 == 0 ? 1 : 0;
			int sy = 1 - sx;
			int run = 0;
			queue.add(start);
			while (!queue.isEmpty())
			{
				long e = queue.poll();
				run++;
				int t = (int) (e >> 2);
				for (int s = -1; s <= 1; s += 2)
				{
					long next = (long) ((t >> 16) + s * sx << 16 | (t & 0xFFFF) + s * sy) << 2 | d;
					if (openEdges.contains(next) && seen.add(next))
					{
						queue.add(next);
					}
				}
			}
			if (run > MAX_DOORWAY_WIDTH)
			{
				wide += run;
			}
		}
		return wide > outline * MAX_OPEN_OUTLINE_SMALL;
	}

	private boolean walkable(int x, int y)
	{
		return (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
	}

	/** Whether a wall blocks moving from (x, y) in direction d. */
	private boolean blocked(int x, int y, int d)
	{
		int nx = x + DX[d];
		int ny = y + DY[d];
		return (flags[x][y] & MOVE[d]) != 0 || (flags[nx][ny] & MOVE[(d + 2) % 4]) != 0;
	}

	/** Whether you can see from tile (x, y) to its neighbour in direction d. */
	public boolean canSee(int x, int y, int d)
	{
		return !sightBlocked(x, y, d);
	}

	/**
	 * Whether light gets from tile (x, y) to its neighbour in direction d. Any wall, fence or closed door stops it,
	 * including ones you can see over, since some walls (like Barbarian Village's) don't block sight in the game data.
	 */
	public boolean lightPasses(int x, int y, int d)
	{
		return !sightBlocked(x, y, d) && !blocked(x, y, d);
	}

	/**
	 * Whether light gets from tile (x, y) to its neighbour in direction d on a plane with the given collision flags,
	 * for lights on another floor than the one mapped into buildings.
	 */
	public static boolean lightPasses(int[][] flags, int x, int y, int d)
	{
		int nx = x + DX[d];
		int ny = y + DY[d];
		int back = (d + 2) % 4;
		return (flags[x][y] & (MOVE[d] | SIGHT[d])) == 0 && (flags[nx][ny] & (MOVE[back] | SIGHT[back])) == 0;
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
